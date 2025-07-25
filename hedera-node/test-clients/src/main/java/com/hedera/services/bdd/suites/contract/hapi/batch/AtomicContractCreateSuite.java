// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi.batch;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isContractWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bytecodePath;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicitContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicitEthereumTransaction;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.contractListWithPropertiesInheritedFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEcdsaPrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.CHAIN_ID;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_BYTECODE_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ERROR_DECODING_BYTESTRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.util.Integers;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.utils.Signing;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of ContractCreateSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(SMART_CONTRACT)
@OrderedInIsolation
@SuppressWarnings("java:S1192") // "string literal should not be duplicated" - this rule makes test suites worse
public class AtomicContractCreateSuite {

    public static final String EMPTY_CONSTRUCTOR_CONTRACT = "EmptyConstructor";
    public static final String PARENT_INFO = "parentInfo";
    private static final String PAYER = "payer";

    private static final Logger log = LogManager.getLogger(AtomicContractCreateSuite.class);

    // The following constants are referenced from -
    // https://github.com/Arachnid/deterministic-deployment-proxy?tab=readme-ov-file#deployment-transaction
    private static final String DEPLOYMENT_SIGNER = "3fab184622dc19b6109349b94811493bf2a45362";
    private static final String DEPLOYMENT_TRANSACTION =
            "f8a58085174876e800830186a08080b853604580600e600039806000f350fe7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe03601600081602082378035828234f58015156039578182fd5b8082525050506014600cf31ba02222222222222222222222222222222222222222222222222222222222222222a02222222222222222222222222222222222222222222222222222222222222222";
    private static final String EXPECTED_DEPLOYER_ADDRESS = "4e59b44847b379578588920ca78fbf26c0b4956c";
    private static final String DEPLOYER = "DeployerContract";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> createContractWithStakingFields() {
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                // refuse eth conversion because ethereum transaction is missing staking fields to map
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(true)
                                .stakedNodeId(0)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .has(contractWith()
                                .isDeclinedReward(true)
                                .noStakedAccountId()
                                .stakedNodeId(0)),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(true)
                                .stakedAccountId("10")
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .has(contractWith()
                                .isDeclinedReward(true)
                                .noStakingNodeId()
                                .stakedAccountId("10")),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(false)
                                .stakedNodeId(0)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakedAccountId()
                                .stakedNodeId(0))
                        .logged(),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(false)
                                .stakedAccountId("10")
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .stakedAccountId("10"))
                        .logged(),
                /* sentinel values throw */
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(false)
                                .stakedAccountId("0.0.0")
                                .hasKnownStatus(INVALID_STAKING_ID)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(false)
                                .stakedNodeId(-1L)
                                .hasKnownStatus(INVALID_STAKING_ID)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> insufficientPayerBalanceUponCreation() {
        return hapiTest(
                cryptoCreate("bankrupt").balance(0L),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .payingWith("bankrupt")
                                .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> disallowCreationsOfEmptyInitCode() {
        final var contract = "EmptyContract";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                // transaction
                atomicBatch(contractCreate(contract)
                                .adminKey(ADMIN_KEY)
                                .entityMemo("Empty Contract")
                                .inlineInitCode(ByteString.EMPTY)
                                .hasKnownStatus(CONTRACT_BYTECODE_EMPTY)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotSendToNonExistentAccount() {
        final var contract = "Multipurpose";

        return hapiTest(
                uploadInitCode(contract),
                atomicBatch(contractCreate(contract).balance(666).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, log) -> {
                    final Object[] donationArgs = {new BigInteger(asSolidityAddress(spec, 666_666L)), "Hey, Ma!"};
                    final var callOp =
                            contractCall(contract, "donate", donationArgs).hasKnownStatus(CONTRACT_REVERT_EXECUTED);
                    allRunFor(spec, callOp);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> invalidSystemInitcodeFileFailsWithInvalidFileId() {
        final var neverToBe = "NeverToBe";
        final var systemFileId = FileID.newBuilder().setFileNum(159).build();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                atomicBatch(explicitContractCreate(neverToBe, (spec, b) -> b.setFileID(systemFileId))
                                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                                // transaction
                                .hasKnownStatus(INVALID_FILE_ID)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                explicitEthereumTransaction(neverToBe, (spec, b) -> {
                            final var signedEthTx = Signing.signMessage(
                                    placeholderEthTx(), getEcdsaPrivateKeyFromSpec(spec, SECP_256K1_SOURCE_KEY));
                            b.setCallData(systemFileId).setEthereumData(ByteString.copyFrom(signedEthTx.encodeTx()));
                        })
                        .hasPrecheck(INVALID_FILE_ID));
    }

    @HapiTest
    final Stream<DynamicTest> createsVanillaContractAsExpectedWithOmittedAdminKey() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .omitAdminKey()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(EMPTY_CONSTRUCTOR_CONTRACT)
                        .has(contractWith().immutableContractKey(EMPTY_CONSTRUCTOR_CONTRACT))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> createEmptyConstructor() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> revertedTryExtCallHasNoSideEffects() {
        final var balance = 3_000;
        final int sendAmount = balance / 3;
        final var contract = "RevertingSendTry";
        final var aBeneficiary = "aBeneficiary";
        final var bBeneficiary = "bBeneficiary";
        final var txn = "txn";

        return hapiTest(
                uploadInitCode(contract),
                atomicBatch(contractCreate(contract).balance(balance).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                cryptoCreate(aBeneficiary).balance(0L),
                cryptoCreate(bBeneficiary).balance(0L),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var aNum = (int) registry.getAccountID(aBeneficiary).getAccountNum();
                    final var bNum = (int) registry.getAccountID(bBeneficiary).getAccountNum();
                    final var sendArgs = new Object[] {(long) sendAmount, (long) aNum, (long) bNum};

                    final var op = contractCall(contract, "sendTo", sendArgs)
                            .gas(110_000)
                            .via(txn);
                    allRunFor(spec, op);
                }),
                getTxnRecord(txn),
                getAccountBalance(aBeneficiary),
                getAccountBalance(bBeneficiary));
    }

    @HapiTest
    final Stream<DynamicTest> createFailsIfMissingSigs() {
        final var shape = listOf(SIMPLE, threshOf(2, 3), threshOf(1, 3));
        final var validSig = shape.signedWith(sigs(ON, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));
        final var invalidSig = shape.signedWith(sigs(OFF, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));

        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .adminKeyShape(shape)
                                .sigControl(forKey(EMPTY_CONSTRUCTOR_CONTRACT, invalidSig))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                                // tokenAssociate,
                                // since we have CONTRACT_ID key
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .adminKeyShape(shape)
                                .sigControl(forKey(EMPTY_CONSTRUCTOR_CONTRACT, validSig))
                                .hasKnownStatus(SUCCESS)
                                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                                // tokenAssociate,
                                // since we have CONTRACT_ID key
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInsufficientGas() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                // refuse eth conversion because ethereum transaction fails in IngestChecker with precheck status
                // INSUFFICIENT_GAS
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .gas(0L)
                                .hasPrecheck(INSUFFICIENT_GAS)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INSUFFICIENT_GAS));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsNegativeGas() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                cryptoCreate(PAYER), // need to use a payer that is not throttle_exempt
                // refuse eth conversion because ethereum transaction fails in IngestChecker with precheck status
                // INSUFFICIENT_GAS
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .gas(-50L)
                                .payingWith(PAYER)
                                .hasPrecheck(BUSY)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INSUFFICIENT_GAS));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInvalidMemo() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .entityMemo(TxnUtils.nAscii(101))
                                .hasKnownStatus(MEMO_TOO_LONG)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .entityMemo(ZERO_BYTE_MEMO)
                                .hasKnownStatus(INVALID_ZERO_BYTE_IN_STRING)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInvalidBytecode() {
        final var contract = "InvalidBytecode";
        return hapiTest(
                uploadInitCode(contract),
                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum transaction
                atomicBatch(contractCreate(contract)
                                .hasKnownStatus(ERROR_DECODING_BYTESTRING)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> revertsNonzeroBalance() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .balance(1L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> delegateContractIdRequiredForTransferInDelegateCall() {
        final var justSendContract = "JustSend";
        final var sendInternalAndDelegateContract = "SendInternalAndDelegate";

        final var beneficiary = "civilian";
        final var totalToSend = 1_000L;
        final var origKey = KeyShape.threshOf(1, SIMPLE, CONTRACT);
        final var revisedKey = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT);
        final var newKey = "delegateContractKey";

        final AtomicReference<ContractID> justSendContractId = new AtomicReference<>();
        final AtomicReference<AccountID> beneficiaryAccountId = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(justSendContract, sendInternalAndDelegateContract),
                // refuse eth conversion because we can't delegate call contract by contract num
                // when it has EVM address alias (isNotPriority check fails)
                atomicBatch(contractCreate(justSendContract)
                                .gas(300_000L)
                                .exposingContractIdTo(justSendContractId::set)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractCreate(sendInternalAndDelegateContract)
                                .gas(300_000L)
                                .balance(2 * totalToSend)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                cryptoCreate(beneficiary)
                        .balance(0L)
                        .keyShape(origKey.signedWith(sigs(ON, sendInternalAndDelegateContract)))
                        .receiverSigRequired(true)
                        .exposingCreatedIdTo(beneficiaryAccountId::set),
                /* Without delegateContractId permissions, the second send via delegate call will
                 * fail, so only half of totalToSend will make it to the beneficiary. (Note the entire
                 * call doesn't fail because exceptional halts in "raw calls" don't automatically
                 * propagate up the stack like a Solidity revert does.) */
                sourcing(() -> contractCall(
                        sendInternalAndDelegateContract,
                        "sendRepeatedlyTo",
                        new BigInteger(asSolidityAddress(justSendContractId.get())),
                        new BigInteger(asSolidityAddress(beneficiaryAccountId.get())),
                        BigInteger.valueOf(totalToSend / 2))),
                getAccountBalance(beneficiary).hasTinyBars(totalToSend / 2),
                /* But now we update the beneficiary to have a delegateContractId */
                newKeyNamed(newKey).shape(revisedKey.signedWith(sigs(ON, sendInternalAndDelegateContract))),
                cryptoUpdate(beneficiary).key(newKey),
                sourcing(() -> contractCall(
                        sendInternalAndDelegateContract,
                        "sendRepeatedlyTo",
                        new BigInteger(asSolidityAddress(justSendContractId.get())),
                        new BigInteger(asSolidityAddress(beneficiaryAccountId.get())),
                        BigInteger.valueOf(totalToSend / 2))),
                getAccountBalance(beneficiary).hasTinyBars(3 * (totalToSend / 2)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotCreateTooLargeContract() {
        ByteString contents;
        try {
            contents = ByteString.copyFrom(Files.readAllBytes(Path.of(bytecodePath("CryptoKitties"))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final var FILE_KEY = "fileKey";
        final var KEY_LIST = "keyList";
        final var ACCOUNT = "acc";
        return hapiTest(
                newKeyNamed(FILE_KEY),
                newKeyListNamed(KEY_LIST, List.of(FILE_KEY)),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS * 10).key(FILE_KEY),
                fileCreate("bytecode")
                        .path(bytecodePath("CryptoKitties"))
                        .hasPrecheck(TRANSACTION_OVERSIZE)
                        .orUnavailableStatus(),
                fileCreate("bytecode").contents("").key(KEY_LIST),
                UtilVerbs.updateLargeFile(ACCOUNT, "bytecode", contents),
                atomicBatch(contractCreate("contract")
                                .bytecode("bytecode")
                                .payingWith(ACCOUNT)
                                .hasKnownStatus(INSUFFICIENT_GAS)
                                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                                // transaction
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> blockTimestampChangesWithinFewSeconds() {
        final var contract = "EmitBlockTimestamp";
        final var firstBlock = "firstBlock";
        final var timeLoggingTxn = "timeLoggingTxn";

        return hapiTest(
                uploadInitCode(contract),
                atomicBatch(contractCreate(contract).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                contractCall(contract, "logNow").via(firstBlock),
                cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(GENESIS, FUNDING, 1)),
                sleepFor(3_000),
                contractCall(contract, "logNow").via(timeLoggingTxn),
                withOpContext((spec, opLog) -> {
                    final var firstBlockOp = getTxnRecord(firstBlock);
                    final var recordOp = getTxnRecord(timeLoggingTxn);
                    allRunFor(spec, firstBlockOp, recordOp);

                    // First block info
                    final var firstBlockRecord = firstBlockOp.getResponseRecord();
                    final var firstBlockLogs =
                            firstBlockRecord.getContractCallResult().getLogInfoList();
                    final var firstBlockTimeLogData =
                            firstBlockLogs.get(0).getData().toByteArray();
                    final var firstBlockTimestamp =
                            Longs.fromByteArray(Arrays.copyOfRange(firstBlockTimeLogData, 24, 32));
                    final var firstBlockHashLogData =
                            firstBlockLogs.get(1).getData().toByteArray();
                    final var firstBlockNumber = Longs.fromByteArray(Arrays.copyOfRange(firstBlockHashLogData, 24, 32));
                    final var firstBlockHash = Bytes32.wrap(Arrays.copyOfRange(firstBlockHashLogData, 32, 64));
                    assertEquals(Bytes32.ZERO, firstBlockHash);

                    // Second block info
                    final var secondBlockRecord = recordOp.getResponseRecord();
                    final var secondBlockLogs =
                            secondBlockRecord.getContractCallResult().getLogInfoList();
                    assertEquals(2, secondBlockLogs.size());
                    final var secondBlockTimeLogData =
                            secondBlockLogs.getFirst().getData().toByteArray();
                    final var secondBlockTimestamp =
                            Longs.fromByteArray(Arrays.copyOfRange(secondBlockTimeLogData, 24, 32));
                    assertNotEquals(firstBlockTimestamp, secondBlockTimestamp, "Block timestamps should change");

                    final var secondBlockHashLogData =
                            secondBlockLogs.get(1).getData().toByteArray();
                    final var secondBlockNumber =
                            Longs.fromByteArray(Arrays.copyOfRange(secondBlockHashLogData, 24, 32));
                    assertNotEquals(firstBlockNumber, secondBlockNumber, "Wrong previous block number");
                    final var secondBlockHash = Bytes32.wrap(Arrays.copyOfRange(secondBlockHashLogData, 32, 64));

                    assertEquals(Bytes32.ZERO, secondBlockHash);
                }),
                contractCallLocal(contract, "getLastBlockHash")
                        .exposingTypedResultsTo(
                                results -> log.info("Results were {}", CommonUtils.hex((byte[]) results[0]))));
    }

    @HapiTest
    final Stream<DynamicTest> tryContractCreateWithMaxAutoAssoc() {
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .refusingEthConversion()
                                .maxAutomaticTokenAssociations(-2)
                                .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .refusingEthConversion()
                                .maxAutomaticTokenAssociations(-200000)
                                .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .refusingEthConversion()
                                .maxAutomaticTokenAssociations(-1)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .has(contractWith().maxAutoAssociations(-1))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> tryContractCreateWithZeroAutoAssoc() {
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .refusingEthConversion()
                                .maxAutomaticTokenAssociations(0)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .has(contractWith().maxAutoAssociations(0))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> tryContractCreateWithBalance() {
        final var contract = "Donor";
        return hapiTest(
                uploadInitCode(contract),
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .refusingEthConversion()
                                .balance(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract)
                        .has(contractWith().maxAutoAssociations(0).balance(ONE_HUNDRED_HBARS))
                        .logged());
    }

    final Stream<DynamicTest> contractCreateShouldChargeTheSame() {
        final var createFeeWithMaxAutoAssoc = 10L;
        final var contract1 = "EmptyOne";
        final var contract2 = "EmptyTwo";
        return hapiTest(
                uploadInitCode(contract1),
                uploadInitCode(contract2),
                atomicBatch(
                                contractCreate(contract1)
                                        .via(contract1)
                                        .adminKey(THRESHOLD)
                                        .refusingEthConversion()
                                        .maxAutomaticTokenAssociations(1)
                                        .hasKnownStatus(SUCCESS)
                                        .batchKey(BATCH_OPERATOR),
                                contractCreate(contract2)
                                        .via(contract2)
                                        .adminKey(THRESHOLD)
                                        .refusingEthConversion()
                                        .maxAutomaticTokenAssociations(1000)
                                        .hasKnownStatus(SUCCESS)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract1)
                        .has(contractWith().maxAutoAssociations(1))
                        .logged(),
                getTxnRecord(contract1).fee(createFeeWithMaxAutoAssoc).logged(),
                getContractInfo(contract2)
                        .has(contractWith().maxAutoAssociations(1000))
                        .logged(),
                getTxnRecord(contract2).fee(createFeeWithMaxAutoAssoc).logged());
    }

    @HapiTest
    final Stream<DynamicTest> vanillaSuccess() {
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                // refuse eth conversion because ethereum transaction is missing admin key
                atomicBatch(contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract).saveToRegistry(PARENT_INFO),
                contractCall(contract, "create").gas(1_000_000L).via("createChildTxn"),
                contractCall(contract, "getIndirect").gas(1_000_000L).via("getChildResultTxn"),
                contractCall(contract, "getAddress").gas(1_000_000L).via("getChildAddressTxn"),
                getTxnRecord("createChildTxn")
                        .saveCreatedContractListToRegistry("createChild")
                        .logged(),
                getTxnRecord("getChildResultTxn")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "getIndirect", contract),
                                                isLiteralResult(new Object[] {BigInteger.valueOf(7L)})))),
                getTxnRecord("getChildAddressTxn")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "getAddress", contract),
                                                isContractWith(contractWith()
                                                        .nonNullContractId()
                                                        .propertiesInheritedFrom(PARENT_INFO)))
                                        .logs(inOrder()))),
                contractListWithPropertiesInheritedFrom("createChildCallResult", 1, PARENT_INFO));
    }

    @HapiTest
    final Stream<DynamicTest> newAccountsCanUsePureContractIdKey() {
        final var contract = "CreateTrivial";
        final var contractControlled = "contractControlled";
        return hapiTest(
                uploadInitCode(contract),
                atomicBatch(contractCreate(contract).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                cryptoCreate(contractControlled).keyShape(CONTRACT.signedWith(contract)),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var contractIdKey = Key.newBuilder()
                            .setContractID(registry.getContractId(contract))
                            .build();
                    final var keyCheck =
                            getAccountInfo(contractControlled).has(accountWith().key(contractIdKey));
                    allRunFor(spec, keyCheck);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> contractWithAutoRenewNeedSignatures() {
        final var contract = "CreateTrivial";
        final var autoRenewAccount = "autoRenewAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(contract),
                cryptoCreate(autoRenewAccount).balance(ONE_HUNDRED_HBARS),
                // refuse eth conversion because ethereum transaction is missing autoRenewAccountId field to map
                atomicBatch(contractCreate(contract)
                                .adminKey(ADMIN_KEY)
                                .autoRenewAccountId(autoRenewAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .refusingEthConversion()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractCreate(contract)
                                .adminKey(ADMIN_KEY)
                                .autoRenewAccountId(autoRenewAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY, autoRenewAccount)
                                .refusingEthConversion()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract).has(ContractInfoAsserts.contractWith().maxAutoAssociations(0)));
    }

    @HapiTest
    final Stream<DynamicTest> contractRevertBlockAndRecordFilesNotContainContractId() {
        final var txn = "contractRevertBlockAndRecordFilesNotContainContractId";
        AtomicReference<Timestamp> ts = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .balance(1L)
                                .via(txn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // check if CONTRACT_REVERT_EXECUTED record DON`T contains expected contractIds
                withOpContext((spec, opLog) -> {
                    final var record = getRecord(spec, txn, CONTRACT_REVERT_EXECUTED);
                    ts.set(record.getConsensusTimestamp());
                    assertRecordContractId(record, false);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> blockAndRecordFilesNotContainContractId() {
        final var txn = "blockAndRecordFilesNotContainContractId";
        AtomicReference<Timestamp> ts = new AtomicReference<>();
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatch(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .via(txn)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                // check if record contains expected contractIds
                withOpContext((spec, opLog) -> {
                    final var record = getRecord(spec, txn, SUCCESS);
                    ts.set(record.getConsensusTimestamp());
                    assertRecordContractId(record, true);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateGas() {
        final String txn = "contractCreateGas";
        return hapiTest(
                uploadInitCode("Storage"),
                atomicBatch(contractCreate("Storage")
                                .gas(124_000L)
                                .via(txn)
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTxnRecord(txn).andAllChildRecords().logged().saveTxnRecordToRegistry(txn),
                withOpContext((spec, ignore) -> {
                    final var gasUsed = spec.registry()
                            .getTransactionRecord(txn)
                            .getContractCreateResult()
                            .getGasUsed();
                    assertEquals(117661, gasUsed);
                }));
    }

    private TransactionRecord getRecord(HapiSpec spec, String txn, ResponseCodeEnum status) {
        final var hapiGetRecord = getTxnRecord(txn);
        allRunFor(spec, hapiGetRecord);
        // Getting record
        final var respRecord = hapiGetRecord.getResponse();
        assertTrue(respRecord.hasTransactionGetRecord());
        final var record = respRecord.getTransactionGetRecord().getTransactionRecord();
        assertEquals(status, record.getReceipt().getStatus());
        return record;
    }

    private void assertRecordContractId(TransactionRecord record, boolean shouldContractIdExists) {
        // check record->receipt->contractId
        assertEquals(shouldContractIdExists, record.getReceipt().hasContractID());
        // check record->contractCreateResult->contractId
        assertEquals(shouldContractIdExists, record.getContractCreateResult().hasContractID());
    }

    private EthTxData placeholderEthTx() {
        return new EthTxData(
                null,
                EthTxData.EthTransactionType.EIP1559,
                Integers.toBytes(CHAIN_ID),
                0L,
                BigInteger.ONE.toByteArray(),
                BigInteger.ONE.toByteArray(),
                BigInteger.ONE.toByteArray(),
                150_000,
                new byte[] {1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4},
                BigInteger.ONE,
                new byte[] {},
                new byte[] {},
                0,
                null,
                null,
                null);
    }
}
