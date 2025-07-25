// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.hedera.hapi.block.stream.output.StateIdentifier.*;
import static com.hedera.pbj.runtime.ProtoConstants.WIRE_TYPE_DELIMITED;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.pbj.runtime.ProtoWriterTools.sizeOfVarInt32;

import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.VirtualMapKey;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.disk.OnDiskValueSerializer;
import com.swirlds.state.merkle.memory.InMemoryValue;
import com.swirlds.state.merkle.memory.InMemoryWritableKVState;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.merkle.singleton.StringLeaf;
import com.swirlds.state.merkle.singleton.ValueLeaf;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;

/** Utility class for working with states. */
public final class StateUtils {

    private static final int UNKNOWN_STATE_ID = -1;

    private static final IntFunction<String> UPGRADE_DATA_FILE_FORMAT =
            n -> String.format("UPGRADE_DATA\\[FileID\\[shardNum=\\d+, realmNum=\\d+, fileNum=%s]]", n);

    /** Cache for pre-computed virtual map keys for singleton states. */
    private static final Bytes[] VIRTUAL_MAP_KEY_CACHE = new Bytes[65536];

    /** Cache to store and retrieve pre-computed labels for specific service states. */
    private static final Map<String, String> LABEL_CACHE = new ConcurrentHashMap<>();

    /** Prevent instantiation */
    private StateUtils() {}

    /**
     * Write the {@code object} to the {@link OutputStream} using the given {@link Codec}.
     *
     * @param out The object to write out
     * @param codec The codec to use. MUST be compatible with the {@code object} type
     * @param object The object to write
     * @return The number of bytes written to the stream.
     * @param <T> The type of the object and associated codec.
     * @throws IOException If the output stream throws it.
     * @throws ClassCastException If the object or codec is not for type {@code T}.
     */
    public static <T> int writeToStream(
            @NonNull final OutputStream out, @NonNull final Codec<T> codec, @Nullable final T object)
            throws IOException {
        final var stream = new WritableStreamingData(out);

        final var byteStream = new ByteArrayOutputStream();
        codec.write(object, new WritableStreamingData(byteStream));

        stream.writeInt(byteStream.size());
        stream.writeBytes(byteStream.toByteArray());
        return byteStream.size();
    }

    /**
     * Read an object from the {@link InputStream} using the given {@link Codec}.
     *
     * @param in The input stream to read from
     * @param codec The codec to use. MUST be compatible with the {@code object} type
     * @return The object read from the stream
     * @param <T> The type of the object and associated codec.
     * @throws IOException If the input stream throws it or parsing fails
     * @throws ClassCastException If the object or codec is not for type {@code T}.
     */
    @Nullable
    public static <T> T readFromStream(@NonNull final InputStream in, @NonNull final Codec<T> codec)
            throws IOException {
        final var stream = new ReadableStreamingData(in);
        final var size = stream.readInt();

        stream.limit((long) size + Integer.BYTES); // +4 for the size
        try {
            return codec.parse(stream);
        } catch (final ParseException ex) {
            throw new IOException(ex);
        }
    }

    /**
     * Registers with the {@link ConstructableRegistry} system a class ID and a class. While this
     * will only be used for in-memory states, it is safe to register for on-disk ones as well.
     *
     * <p>The implementation will take the service name and the state key and compute a hash for it.
     * It will then convert the hash to a long, and use that as the class ID. It will then register
     * an {@link InMemoryWritableKVState}'s value merkle type to be deserialized, answering with the
     * generated class ID.
     *
     * @deprecated Registrations should be removed when there are no longer any objects of the relevant class.
     * Once all registrations have been removed, this method itself should be deleted.
     * See <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/19416">GitHub issue</a>.
     *
     * @param md The state metadata
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Deprecated
    public static void registerWithSystem(
            @NonNull final StateMetadata md, @NonNull ConstructableRegistry constructableRegistry) {
        // Register with the system the uniqueId as the "classId" of an InMemoryValue. There can be
        // multiple id's associated with InMemoryValue. The secret is that the supplier captures the
        // various delegate writers and parsers, and so can parse/write different types of data
        // based on the id.
        try {
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    InMemoryValue.class,
                    () -> new InMemoryValue(
                            md.inMemoryValueClassId(),
                            md.stateDefinition().keyCodec(),
                            md.stateDefinition().valueCodec())));
            // FUTURE WORK: remove OnDiskKey registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKey.class,
                    () -> new OnDiskKey<>(
                            md.onDiskKeyClassId(), md.stateDefinition().keyCodec())));
            // FUTURE WORK: remove OnDiskKeySerializer registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskKeySerializer.class,
                    () -> new OnDiskKeySerializer<>(
                            md.onDiskKeySerializerClassId(),
                            md.onDiskKeyClassId(),
                            md.stateDefinition().keyCodec())));
            // FUTURE WORK: remove OnDiskValue registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValue.class,
                    () -> new OnDiskValue<>(
                            md.onDiskValueClassId(), md.stateDefinition().valueCodec())));
            // FUTURE WORK: remove OnDiskValueSerializer registration, once there are no objects of this class
            // in existing state snapshots
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    OnDiskValueSerializer.class,
                    () -> new OnDiskValueSerializer<>(
                            md.onDiskValueSerializerClassId(),
                            md.onDiskValueClassId(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    SingletonNode.class,
                    () -> new SingletonNode<>(
                            md.serviceName(),
                            md.stateDefinition().stateKey(),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec(),
                            null)));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    QueueNode.class,
                    () -> new QueueNode<>(
                            md.serviceName(),
                            md.stateDefinition().stateKey(),
                            md.queueNodeClassId(),
                            md.singletonClassId(),
                            md.stateDefinition().valueCodec())));
            constructableRegistry.registerConstructable(new ClassConstructorPair(StringLeaf.class, StringLeaf::new));
            constructableRegistry.registerConstructable(new ClassConstructorPair(
                    ValueLeaf.class,
                    () -> new ValueLeaf<>(
                            md.singletonClassId(), md.stateDefinition().valueCodec())));
        } catch (ConstructableRegistryException e) {
            // This is a fatal error.
            throw new IllegalStateException(
                    "Failed to register with the system '"
                            + md.serviceName()
                            + ":"
                            + md.stateDefinition().stateKey()
                            + "'",
                    e);
        }
    }

    /**
     * Returns the state id for the given service and state key.
     *
     * @param serviceName the service name
     * @param stateKey the state key
     * @return the state id
     */
    public static int stateIdFor(@NonNull final String serviceName, @NonNull final String stateKey) {
        final var stateId =
                switch (serviceName) {
                    case "AddressBookService" ->
                        switch (stateKey) {
                            case "NODES" -> STATE_ID_NODES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "BlockRecordService" ->
                        switch (stateKey) {
                            case "BLOCKS" -> STATE_ID_BLOCK_INFO.protoOrdinal();
                            case "RUNNING_HASHES" -> STATE_ID_RUNNING_HASHES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "BlockStreamService" ->
                        switch (stateKey) {
                            case "BLOCK_STREAM_INFO" -> STATE_ID_BLOCK_STREAM_INFO.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "CongestionThrottleService" ->
                        switch (stateKey) {
                            case "CONGESTION_LEVEL_STARTS" -> STATE_ID_CONGESTION_STARTS.protoOrdinal();
                            case "THROTTLE_USAGE_SNAPSHOTS" -> STATE_ID_THROTTLE_USAGE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ConsensusService" ->
                        switch (stateKey) {
                            case "TOPICS" -> STATE_ID_TOPICS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ContractService" ->
                        switch (stateKey) {
                            case "BYTECODE" -> STATE_ID_CONTRACT_BYTECODE.protoOrdinal();
                            case "STORAGE" -> STATE_ID_CONTRACT_STORAGE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "EntityIdService" ->
                        switch (stateKey) {
                            case "ENTITY_ID" -> STATE_ID_ENTITY_ID.protoOrdinal();
                            case "ENTITY_COUNTS" -> STATE_ID_ENTITY_COUNTS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "FeeService" ->
                        switch (stateKey) {
                            case "MIDNIGHT_RATES" -> STATE_ID_MIDNIGHT_RATES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "FileService" -> {
                        if ("FILES".equals(stateKey)) {
                            yield STATE_ID_FILES.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(150))) {
                            yield STATE_ID_UPGRADE_DATA_150.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(151))) {
                            yield STATE_ID_UPGRADE_DATA_151.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(152))) {
                            yield STATE_ID_UPGRADE_DATA_152.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(153))) {
                            yield STATE_ID_UPGRADE_DATA_153.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(154))) {
                            yield STATE_ID_UPGRADE_DATA_154.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(155))) {
                            yield STATE_ID_UPGRADE_DATA_155.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(156))) {
                            yield STATE_ID_UPGRADE_DATA_156.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(157))) {
                            yield STATE_ID_UPGRADE_DATA_157.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(158))) {
                            yield STATE_ID_UPGRADE_DATA_158.protoOrdinal();
                        } else if (stateKey.matches(UPGRADE_DATA_FILE_FORMAT.apply(159))) {
                            yield STATE_ID_UPGRADE_DATA_159.protoOrdinal();
                        } else {
                            yield UNKNOWN_STATE_ID;
                        }
                    }
                    case "FreezeService" ->
                        switch (stateKey) {
                            case "FREEZE_TIME" -> STATE_ID_FREEZE_TIME.protoOrdinal();
                            case "UPGRADE_FILE_HASH" -> STATE_ID_UPGRADE_FILE_HASH.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "PlatformStateService" ->
                        switch (stateKey) {
                            case "PLATFORM_STATE" -> STATE_ID_PLATFORM_STATE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "RecordCache" ->
                        switch (stateKey) {
                            case "TransactionReceiptQueue" -> STATE_ID_TRANSACTION_RECEIPTS_QUEUE.protoOrdinal();
                            // There is no such queue, but this needed for V0540RecordCacheSchema schema migration
                            case "TransactionRecordQueue" -> STATE_ID_TRANSACTION_RECEIPTS_QUEUE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "RosterService" ->
                        switch (stateKey) {
                            case "ROSTERS" -> STATE_ID_ROSTERS.protoOrdinal();
                            case "ROSTER_STATE" -> STATE_ID_ROSTER_STATE.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "ScheduleService" ->
                        switch (stateKey) {
                            case "SCHEDULES_BY_EQUALITY" -> STATE_ID_SCHEDULES_BY_EQUALITY.protoOrdinal();
                            case "SCHEDULES_BY_EXPIRY_SEC" -> STATE_ID_SCHEDULES_BY_EXPIRY.protoOrdinal();
                            case "SCHEDULES_BY_ID" -> STATE_ID_SCHEDULES_BY_ID.protoOrdinal();
                            case "SCHEDULE_ID_BY_EQUALITY" -> STATE_ID_SCHEDULE_ID_BY_EQUALITY.protoOrdinal();
                            case "SCHEDULED_COUNTS" -> STATE_ID_SCHEDULED_COUNTS.protoOrdinal();
                            case "SCHEDULED_ORDERS" -> STATE_ID_SCHEDULED_ORDERS.protoOrdinal();
                            case "SCHEDULED_USAGES" -> STATE_ID_SCHEDULED_USAGES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "TokenService" ->
                        switch (stateKey) {
                            case "ACCOUNTS" -> STATE_ID_ACCOUNTS.protoOrdinal();
                            case "ALIASES" -> STATE_ID_ALIASES.protoOrdinal();
                            case "NFTS" -> STATE_ID_NFTS.protoOrdinal();
                            case "PENDING_AIRDROPS" -> STATE_ID_PENDING_AIRDROPS.protoOrdinal();
                            case "STAKING_INFOS" -> STATE_ID_STAKING_INFO.protoOrdinal();
                            case "STAKING_NETWORK_REWARDS" -> STATE_ID_NETWORK_REWARDS.protoOrdinal();
                            case "TOKEN_RELS" -> STATE_ID_TOKEN_RELATIONS.protoOrdinal();
                            case "TOKENS" -> STATE_ID_TOKENS.protoOrdinal();
                            case "NODE_REWARDS" -> STATE_ID_NODE_REWARDS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "TssBaseService" ->
                        switch (stateKey) {
                            case "TSS_MESSAGES" -> STATE_ID_TSS_MESSAGES.protoOrdinal();
                            case "TSS_VOTES" -> STATE_ID_TSS_VOTES.protoOrdinal();
                            case "TSS_ENCRYPTION_KEYS" -> STATE_ID_TSS_ENCRYPTION_KEYS.protoOrdinal();
                            case "TSS_STATUS" -> STATE_ID_TSS_STATUS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "HintsService" ->
                        switch (stateKey) {
                            case "HINTS_KEY_SETS" -> STATE_ID_HINTS_KEY_SETS.protoOrdinal();
                            case "ACTIVE_HINT_CONSTRUCTION" -> STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal();
                            case "NEXT_HINT_CONSTRUCTION" -> STATE_ID_NEXT_HINTS_CONSTRUCTION.protoOrdinal();
                            case "PREPROCESSING_VOTES" -> STATE_ID_PREPROCESSING_VOTES.protoOrdinal();
                            case "CRS_STATE" -> STATE_ID_CRS_STATE.protoOrdinal();
                            case "CRS_PUBLICATIONS" -> STATE_ID_CRS_PUBLICATIONS.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    case "HistoryService" ->
                        switch (stateKey) {
                            case "LEDGER_ID" -> STATE_ID_LEDGER_ID.protoOrdinal();
                            case "PROOF_KEY_SETS" -> STATE_ID_PROOF_KEY_SETS.protoOrdinal();
                            case "ACTIVE_PROOF_CONSTRUCTION" -> STATE_ID_ACTIVE_PROOF_CONSTRUCTION.protoOrdinal();
                            case "NEXT_PROOF_CONSTRUCTION" -> STATE_ID_NEXT_PROOF_CONSTRUCTION.protoOrdinal();
                            case "HISTORY_SIGNATURES" -> STATE_ID_HISTORY_SIGNATURES.protoOrdinal();
                            case "PROOF_VOTES" -> STATE_ID_PROOF_VOTES.protoOrdinal();
                            default -> UNKNOWN_STATE_ID;
                        };
                    default -> UNKNOWN_STATE_ID;
                };
        if (stateId == UNKNOWN_STATE_ID) {
            throw new IllegalArgumentException("Unknown state '" + serviceName + "." + stateKey + "'");
        } else {
            return stateId;
        }
    }

    /**
     * Validates that the state ID for the given service name and state key is within valid range.
     *
     * @param serviceName the service name
     * @param stateKey the state key
     * @return the validated state ID (between 0 and 65535 inclusive)
     * @throws IllegalArgumentException if the state ID is outside the valid range
     */
    private static int getValidatedStateId(@NonNull final String serviceName, @NonNull final String stateKey) {
        final int stateId = stateIdFor(serviceName, stateKey);
        if (stateId < 0 || stateId > 65535) {
            throw new IllegalArgumentException("State ID " + stateId + " must fit in [0..65535]");
        }
        return stateId;
    }

    /**
     * Computes the label for a Merkle node given the service name and state key.
     * <p>
     * The label is computed as "serviceName.stateKey". The result is cached so that repeated calls
     * with the same parameters return the same string without redoing the concatenation.
     * </p>
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @return the computed label
     */
    public static String computeLabel(@NonNull final String serviceName, @NonNull final String stateKey) {
        final String key = Objects.requireNonNull(serviceName) + "." + Objects.requireNonNull(stateKey);
        return LABEL_CACHE.computeIfAbsent(key, k -> k);
    }

    /**
     * Decomposes a computed label into its service name and state key components.
     * <p>
     * This method performs the inverse operation of {@link #computeLabel(String, String)}.
     * It assumes the label is in the format "serviceName.stateKey".
     * </p>
     *
     * @param label the computed label
     * @return a {@link Pair} where the left element is the service name and the right element is the state key
     * @throws IllegalArgumentException if the label does not contain a period ('.') as expected
     * @throws NullPointerException     if the label is {@code null}
     */
    public static Pair<String, String> decomposeLabel(final String label) {
        Objects.requireNonNull(label, "Label must not be null");

        int delimiterIndex = label.indexOf('.');
        if (delimiterIndex < 0) {
            throw new IllegalArgumentException("Label must be in the format 'serviceName.stateKey'");
        }

        final String serviceName = label.substring(0, delimiterIndex);
        final String stateKey = label.substring(delimiterIndex + 1);
        return Pair.of(serviceName, stateKey);
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a singleton state, serializes into a {@link Bytes} object
     * and returns it.
     * The result is cached to avoid repeated allocations.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @return a {@link VirtualMapKey} for the singleton serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getVirtualMapKeyForSingleton(
            @NonNull final String serviceName, @NonNull final String stateKey) {
        final int stateId = getValidatedStateId(serviceName, stateKey);
        Bytes key = VIRTUAL_MAP_KEY_CACHE[stateId];
        if (key == null) {
            key = VirtualMapKey.PROTOBUF.toBytes(new VirtualMapKey(
                    new OneOf<>(VirtualMapKey.KeyOneOfType.SINGLETON, SingletonType.fromProtobufOrdinal(stateId))));
            VIRTUAL_MAP_KEY_CACHE[stateId] = key;
        }
        return key;
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a queue element, serializes into a {@link Bytes} object
     * and returns it.
     *
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param index       the queue element index
     * @return a {@link VirtualMapKey} for a queue element serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static Bytes getVirtualMapKeyForQueue(
            @NonNull final String serviceName, @NonNull final String stateKey, final long index) {
        return VirtualMapKey.PROTOBUF.toBytes(new VirtualMapKey(new OneOf<>(
                VirtualMapKey.KeyOneOfType.fromProtobufOrdinal(getValidatedStateId(serviceName, stateKey)), index)));
    }

    /**
     * Creates an instance of {@link VirtualMapKey} for a k/v state, serializes into a {@link Bytes} object
     * and returns it.
     *
     * @param <K>         the type of the key
     * @param serviceName the service name
     * @param stateKey    the state key
     * @param key         the key object
     * @return a {@link VirtualMapKey} for a k/v state, serialized into {@link Bytes} object
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static <K> Bytes getVirtualMapKeyForKv(
            @NonNull final String serviceName, @NonNull final String stateKey, final K key) {
        return VirtualMapKey.PROTOBUF.toBytes(new VirtualMapKey(new OneOf<>(
                VirtualMapKey.KeyOneOfType.fromProtobufOrdinal(getValidatedStateId(serviceName, stateKey)), key)));
    }

    /**
     * Creates Protocol Buffer encoded byte array for a VirtualMapKey field.
     * Follows protobuf encoding format: tag (field number + wire type), length, and value.
     *
     * @param serviceName       the service name
     * @param stateKey          the state key
     * @param keyObjectBytes    the serialized key object
     * @return Properly encoded Protocol Buffer byte array
     * @throws IllegalArgumentException if the derived state ID is not within the range [0..65535]
     */
    public static byte[] createVirtualMapKeyBytesForKV(
            @NonNull final String serviceName, @NonNull final String stateKey, byte[] keyObjectBytes) {
        final int stateId = getValidatedStateId(serviceName, stateKey);
        // This matches the Protocol Buffer tag format: (field_number << TAG_TYPE_BITS) | wire_type
        int tag = (stateId << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal();

        ByteBuffer byteBuffer = ByteBuffer.allocate(sizeOfVarInt32(tag)
                + sizeOfVarInt32(keyObjectBytes.length) /* length */
                + keyObjectBytes.length /* key bytes */);
        BufferedData bufferedData = BufferedData.wrap(byteBuffer);

        bufferedData.writeVarInt(tag, false);
        bufferedData.writeVarInt(keyObjectBytes.length, false);
        bufferedData.writeBytes(keyObjectBytes);

        return byteBuffer.array();
    }
}
