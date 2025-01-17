/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.graphql.internal.pojoadapter;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.api.graphql.GraphQLContextType;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.api.query.TransactionWithMetadata;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions;
import org.hyperledger.besu.ethereum.mainnet.ImmutableTransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.transaction.CallParameter;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulatorResult;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import graphql.schema.DataFetchingEnvironment;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

@SuppressWarnings("unused") // reflected by GraphQL
public class PendingStateAdapter extends AdapterBase {

  private final PendingTransactions pendingTransactions;

  public PendingStateAdapter(final PendingTransactions pendingTransactions) {
    this.pendingTransactions = pendingTransactions;
  }

  public Integer getTransactionCount() {
    return pendingTransactions.size();
  }

  public List<TransactionAdapter> getTransactions() {
    return pendingTransactions.getPendingTransactions().stream()
        .map(PendingTransaction::getTransaction)
        .map(TransactionWithMetadata::new)
        .map(TransactionAdapter::new)
        .toList();
  }

  // until the miner can expose the current "proposed block" we have no
  // speculative environment, so estimate against latest.
  public AccountAdapter getAccount(final DataFetchingEnvironment dataFetchingEnvironment) {
    final BlockchainQueries blockchainQuery =
        dataFetchingEnvironment.getGraphQlContext().get(GraphQLContextType.BLOCKCHAIN_QUERIES);
    final Address addr = dataFetchingEnvironment.getArgument("address");
    final Long blockNumber = dataFetchingEnvironment.getArgument("blockNumber");
    final long latestBlockNumber = blockchainQuery.latestBlock().get().getHeader().getNumber();
    return blockchainQuery
        .getAndMapWorldState(latestBlockNumber, ws -> Optional.ofNullable(ws.get(addr)))
        .map(AccountAdapter::new)
        .orElseGet(() -> new AccountAdapter(null));
  }

  // until the miner can expose the current "proposed block" we have no
  // speculative environment, so estimate against latest.
  public Optional<Long> getEstimateGas(final DataFetchingEnvironment environment) {
    final Optional<CallResult> result = getCall(environment);
    return result.map(CallResult::getGasUsed);
  }

  // until the miner can expose the current "proposed block" we have no
  // speculative environment, so estimate against latest.
  public Optional<CallResult> getCall(final DataFetchingEnvironment environment) {
    final Map<String, Object> callData = environment.getArgument("data");
    final Address from = (Address) callData.get("from");
    final Address to = (Address) callData.get("to");
    final Long gas = (Long) callData.get("gas");
    final UInt256 gasPrice = (UInt256) callData.get("gasPrice");
    final UInt256 value = (UInt256) callData.get("value");
    final Bytes data = (Bytes) callData.get("data");

    final BlockchainQueries query = getBlockchainQueries(environment);
    final ProtocolSchedule protocolSchedule =
        environment.getGraphQlContext().get(GraphQLContextType.PROTOCOL_SCHEDULE);

    final TransactionSimulator transactionSimulator =
        new TransactionSimulator(
            query.getBlockchain(), query.getWorldStateArchive(), protocolSchedule);

    long gasParam = -1;
    Wei gasPriceParam = null;
    Wei valueParam = null;
    if (gas != null) {
      gasParam = gas;
    }
    if (gasPrice != null) {
      gasPriceParam = Wei.of(gasPrice);
    }
    if (value != null) {
      valueParam = Wei.of(value);
    }
    final CallParameter param =
        new CallParameter(from, to, gasParam, gasPriceParam, valueParam, data);

    ImmutableTransactionValidationParams.Builder transactionValidationParams =
        ImmutableTransactionValidationParams.builder()
            .from(TransactionValidationParams.transactionSimulator());
    transactionValidationParams.isAllowExceedingBalance(true);

    final Optional<TransactionSimulatorResult> opt =
        transactionSimulator.process(
            param,
            transactionValidationParams.build(),
            OperationTracer.NO_TRACING,
            query.getBlockchain().getChainHeadHeader());

    if (opt.isPresent()) {
      final TransactionSimulatorResult result = opt.get();
      long status = 0;
      if (result.isSuccessful()) {
        status = 1;
      }
      final CallResult callResult =
          new CallResult(status, result.getGasEstimate(), result.getOutput());
      return Optional.of(callResult);
    }
    return Optional.empty();
  }
}
