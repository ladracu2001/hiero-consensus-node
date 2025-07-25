// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.computeLabel;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueIterate;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.merkle.queue.QueueState;
import com.swirlds.state.merkle.queue.QueueStateCodec;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A helper class for managing an on-disk queue using a {@link VirtualMap} as the core storage mechanism.
 *
 * <p>This class was created to extract repetitive code from
 * {@link OnDiskWritableQueueState} and {@link OnDiskReadableQueueState}.
 *
 * <p><b>Why is it needed?</b></p>
 * To avoid duplication and simplify how Queue State classes interact with the underlying
 * {@link VirtualMap} storage.
 *
 * <p><b>What does it do?</b></p>
 * This class is responsible for:
 * <ul>
 *     <li>Providing functionality to iterate over elements stored in a queue-like manner.</li>
 *     <li>Retrieving elements using specific indices.</li>
 *     <li>Managing and updating metadata for the queue state, including head and tail pointers.</li>
 *     <li>Ensuring efficient interaction with the underlying {@link VirtualMap} storage and handling
 *         encoding/decoding operations with {@link Codec}.</li>
 * </ul>
 *
 * <p><b>Where is it used?</b></p>
 * It is used in {@link OnDiskWritableQueueState} and {@link OnDiskReadableQueueState} to perform
 * operations like adding, removing, or reading queue elements while ensuring persistence and
 * consistency across multiple layers of the queue implementation.
 *
 * @param <E> the type of elements stored in the on-disk queue
 */
public final class OnDiskQueueHelper<E> {

    /**
     * The name of the service that owns this queue's state.
     */
    @NonNull
    private final String serviceName;

    /**
     * The unique key for identifying the state of this queue.
     */
    @NonNull
    private final String stateKey;

    /**
     * The core storage mechanism for the queue data within the on-disk queue.
     */
    @NonNull
    private final VirtualMap virtualMap;

    /**
     * The codec for the elements of the queue.
     */
    @NonNull
    private final Codec<E> valueCodec;

    /**
     * An empty iterator used as a placeholder when no elements are available.
     */
    private final QueueIterator EMPTY_ITERATOR = new QueueIterator(0, 0);

    /**
     * Creates an instance of the on-disk queue helper.
     *
     * @param serviceName The name of the service that owns the queue's state.
     * @param stateKey The unique key for identifying the queue's state.
     * @param virtualMap The storage mechanism for the queue's data.
     * @param valueCodec The codec for the elements of the queue.
     */
    public OnDiskQueueHelper(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final VirtualMap virtualMap,
            @NonNull final Codec<E> valueCodec) {
        this.serviceName = requireNonNull(serviceName);
        this.stateKey = requireNonNull(stateKey);
        this.virtualMap = requireNonNull(virtualMap);
        this.valueCodec = requireNonNull(valueCodec);
    }

    /**
     * Creates an iterator to traverse the elements in the queue's data source.
     *
     * @return An iterator for the elements of the queue.
     */
    @NonNull
    public Iterator<E> iterateOnDataSource() {
        final QueueState state = getState();
        if (state == null) {
            return EMPTY_ITERATOR;
        } else {
            final QueueIterator it = new QueueIterator(state.getHead(), state.getTail());
            // Log to transaction state log, what was iterated
            logQueueIterate(computeLabel(serviceName, stateKey), state.getTail() - state.getHead(), it);
            it.reset();
            return it;
        }
    }

    /**
     * Retrieves an element from the queue's data store by its index.
     *
     * @param index The index of the element to retrieve.
     * @return The element at the specified index.
     * @throws IllegalStateException If the element is not found in the store.
     */
    @NonNull
    public E getFromStore(final long index) {
        final var value = virtualMap.get(StateUtils.getVirtualMapKeyForQueue(serviceName, stateKey, index), valueCodec);
        if (value == null) {
            throw new IllegalStateException("Can't find queue element at index " + index + " in the store");
        }
        return value;
    }

    /**
     * Retrieves the current state of the queue.
     *
     * @return The current state of the queue.
     */
    public QueueState getState() {
        final QueueState state = virtualMap.get(
                StateUtils.getVirtualMapKeyForSingleton(serviceName, stateKey), QueueStateCodec.INSTANCE);
        if (state == null) {
            return null;
        }
        // FUTURE WORK: optimize performance here, see https://github.com/hiero-ledger/hiero-consensus-node/issues/19670
        return new QueueState(state.getHead(), state.getTail());
    }

    /**
     * Updates the state of the queue in the data store.
     *
     * @param state The new state to set for the queue.
     */
    public void updateState(@NonNull final QueueState state) {
        virtualMap.put(StateUtils.getVirtualMapKeyForSingleton(serviceName, stateKey), state, QueueStateCodec.INSTANCE);
    }

    /**
     * Utility class for iterating over queue elements within a specific range.
     */
    private class QueueIterator implements Iterator<E> {

        /**
         * The starting position of the iteration (inclusive).
         */
        private final long start;

        /**
         * The ending position of the iteration (exclusive).
         */
        private final long limit;

        /**
         * The current position of the iterator, where {@code start <= current < limit}.
         */
        private long current;

        /**
         * Creates a new iterator for the specified range.
         *
         * @param start The starting position of the iteration (inclusive).
         * @param limit The ending position of the iteration (exclusive).
         */
        public QueueIterator(final long start, final long limit) {
            this.start = start;
            this.limit = limit;
            reset();
        }

        /**
         * Checks if there are more elements to iterate over.
         *
         * @return {@code true} if there are more elements, {@code false} otherwise.
         */
        @Override
        public boolean hasNext() {
            return current < limit;
        }

        /**
         * Returns the next element in the iteration.
         *
         * @return The next element in the queue.
         * @throws NoSuchElementException If no more elements are available.
         * @throws ConcurrentModificationException If the queue was modified during iteration.
         */
        @Override
        public E next() {
            if (current == limit) {
                throw new NoSuchElementException();
            }
            try {
                return getFromStore(current++);
            } catch (final IllegalStateException e) {
                throw new ConcurrentModificationException(e);
            }
        }

        /**
         * Resets the iterator to the starting position.
         */
        void reset() {
            current = start;
        }
    }
}
