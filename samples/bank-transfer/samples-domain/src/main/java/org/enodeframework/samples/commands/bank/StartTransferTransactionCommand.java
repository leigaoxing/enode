package org.enodeframework.samples.commands.bank;

import org.enodeframework.commanding.AbstractCommandMessage;
import org.enodeframework.samples.domain.bank.transfertransaction.TransferTransactionInfo;

/**
 * 发起一笔转账交易
 */
public class StartTransferTransactionCommand extends AbstractCommandMessage<String> {
    /**
     * 转账交易信息
     */
    public TransferTransactionInfo transferTransactionInfo;

    public StartTransferTransactionCommand() {
    }

    public StartTransferTransactionCommand(String transactionId, TransferTransactionInfo transferTransactionInfo) {
        super(transactionId);
        this.transferTransactionInfo = transferTransactionInfo;
    }
}
