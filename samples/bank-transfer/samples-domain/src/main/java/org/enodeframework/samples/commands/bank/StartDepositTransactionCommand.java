package org.enodeframework.samples.commands.bank;

import org.enodeframework.commanding.AbstractCommandMessage;

/**
 * 发起一笔存款交易
 */
public class StartDepositTransactionCommand extends AbstractCommandMessage<String> {
    /**
     * 账户ID
     */
    public String accountId;
    /**
     * 存款金额
     */
    public double amount;

    public StartDepositTransactionCommand() {
    }

    public StartDepositTransactionCommand(String transactionId, String accountId, double amount) {
        super(transactionId);
        this.accountId = accountId;
        this.amount = amount;
    }
}
