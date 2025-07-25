// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.hedera.pbj.runtime.ProtoParserTools.readNextFieldNumber;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.VirtualMapKey;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.NoSuchElementException;

public class OnDiskIterator<K, V> extends BackedOnDiskIterator<K, V> {

    private final int stateId;
    private final MerkleIterator<MerkleNode> itr;
    private K next = null;

    public OnDiskIterator(@NonNull final VirtualMap virtualMap, @NonNull final Codec<K> keyCodec, final int stateId) {
        super(virtualMap, keyCodec);
        this.stateId = stateId;
        itr = requireNonNull(virtualMap).treeIterator();
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }
        while (itr.hasNext()) {
            final MerkleNode merkleNode = itr.next();
            if (merkleNode instanceof VirtualLeafNode leaf) {
                final Bytes k = leaf.getKey();
                // Here we rely on the fact that `VirtualMapKey` has a single `OneOf` field.
                // So, the next field number is the key type
                final int nextNextStateId = readNextFieldNumber(k.toReadableSequentialData());
                if (stateId == nextNextStateId) {
                    try {
                        final VirtualMapKey parse = VirtualMapKey.PROTOBUF.parse(k);
                        this.next = parse.key().as();
                        return true;
                    } catch (final ParseException e) {
                        throw new RuntimeException("Failed to parse a key", e);
                    }
                }
            }
        }
        return false;
    }

    @Override
    public K next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        final var k = next;
        next = null;
        return k;
    }
}
