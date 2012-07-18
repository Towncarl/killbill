/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.invoice.dao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.Transaction;
import org.skife.jdbi.v2.TransactionStatus;

import com.ning.billing.ErrorCode;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceApiException;
import com.ning.billing.invoice.api.InvoiceItem;
import com.ning.billing.invoice.api.InvoiceItemType;
import com.ning.billing.invoice.api.InvoicePayment;
import com.ning.billing.invoice.api.InvoicePayment.InvoicePaymentType;
import com.ning.billing.invoice.generator.InvoiceDateUtils;
import com.ning.billing.invoice.model.CreditAdjInvoiceItem;
import com.ning.billing.invoice.model.CreditBalanceAdjInvoiceItem;
import com.ning.billing.invoice.model.DefaultInvoice;
import com.ning.billing.invoice.model.DefaultInvoicePayment;
import com.ning.billing.invoice.model.RecurringInvoiceItem;
import com.ning.billing.invoice.model.RefundAdjInvoiceItem;
import com.ning.billing.invoice.notification.NextBillingDatePoster;
import com.ning.billing.util.ChangeType;
import com.ning.billing.util.api.TagApiException;
import com.ning.billing.util.api.TagUserApi;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.clock.Clock;
import com.ning.billing.util.dao.EntityAudit;
import com.ning.billing.util.dao.ObjectType;
import com.ning.billing.util.dao.TableName;
import com.ning.billing.util.tag.ControlTagType;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.inject.Inject;

public class DefaultInvoiceDao implements InvoiceDao {

    private final InvoiceSqlDao invoiceSqlDao;
    private final InvoicePaymentSqlDao invoicePaymentSqlDao;
    private final TagUserApi tagUserApi;
    private final NextBillingDatePoster nextBillingDatePoster;
    private final InvoiceItemSqlDao invoiceItemSqlDao;
    private final Clock clock;

    @Inject
    public DefaultInvoiceDao(final IDBI dbi,
                             final NextBillingDatePoster nextBillingDatePoster,
                             final TagUserApi tagUserApi,
                             final Clock clock) {
        this.invoiceSqlDao = dbi.onDemand(InvoiceSqlDao.class);
        this.invoicePaymentSqlDao = dbi.onDemand(InvoicePaymentSqlDao.class);
        this.invoiceItemSqlDao = dbi.onDemand(InvoiceItemSqlDao.class);
        this.nextBillingDatePoster = nextBillingDatePoster;
        this.tagUserApi = tagUserApi;
        this.clock = clock;
    }

    @Override
    public List<Invoice> getInvoicesByAccount(final UUID accountId) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                final List<Invoice> invoices = invoiceDao.getInvoicesByAccount(accountId.toString());
                populateChildren(invoices, invoiceDao);
                return invoices;
            }
        });
    }

    @Override
    public List<Invoice> getAllInvoicesByAccount(final UUID accountId) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                return getAllInvoicesByAccountFromTransaction(accountId, invoiceDao);
            }
        });
    }

    @Override
    public List<Invoice> getInvoicesByAccount(final UUID accountId, final LocalDate fromDate) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                final List<Invoice> invoices = invoiceDao.getInvoicesByAccountAfterDate(accountId.toString(), fromDate.toDateTimeAtStartOfDay().toDate());

                populateChildren(invoices, invoiceDao);

                return invoices;
            }
        });
    }

    @Override
    public List<Invoice> get() {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                final List<Invoice> invoices = invoiceDao.get();

                populateChildren(invoices, invoiceDao);

                return invoices;
            }
        });
    }

    @Override
    public Invoice getById(final UUID invoiceId) {
        return invoiceSqlDao.inTransaction(new Transaction<Invoice, InvoiceSqlDao>() {
            @Override
            public Invoice inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                final Invoice invoice = invoiceDao.getById(invoiceId.toString());

                if (invoice != null) {
                    populateChildren(invoice, invoiceDao);
                }

                return invoice;
            }
        });
    }

    @Override
    public Invoice getByNumber(final Integer number) {
        // The invoice number is just the record id
        return invoiceSqlDao.getByRecordId(number.longValue());
    }

    @Override
    public void create(final Invoice invoice, final int billCycleDayUTC, final CallContext context) {
        invoiceSqlDao.inTransaction(new Transaction<Void, InvoiceSqlDao>() {
            @Override
            public Void inTransaction(final InvoiceSqlDao transactional, final TransactionStatus status) throws Exception {
                final Invoice currentInvoice = transactional.getById(invoice.getId().toString());
                if (currentInvoice == null) {
                    final List<EntityAudit> audits = new ArrayList<EntityAudit>();

                    transactional.create(invoice, context);
                    final Long recordId = transactional.getRecordId(invoice.getId().toString());
                    audits.add(new EntityAudit(TableName.INVOICES, recordId, ChangeType.INSERT));

                    List<Long> recordIdList;

                    final List<InvoiceItem> invoiceItems = invoice.getInvoiceItems();
                    final InvoiceItemSqlDao transInvoiceItemSqlDao = transactional.become(InvoiceItemSqlDao.class);
                    transInvoiceItemSqlDao.batchCreateFromTransaction(invoiceItems, context);
                    recordIdList = transInvoiceItemSqlDao.getRecordIds(invoice.getId().toString());
                    audits.addAll(createAudits(TableName.INVOICE_ITEMS, recordIdList));

                    final List<InvoiceItem> recurringInvoiceItems = invoice.getInvoiceItems(RecurringInvoiceItem.class);
                    notifyOfFutureBillingEvents(transactional, invoice.getAccountId(), billCycleDayUTC, recurringInvoiceItems);

                    final List<InvoicePayment> invoicePayments = invoice.getPayments();
                    final InvoicePaymentSqlDao invoicePaymentSqlDao = transactional.become(InvoicePaymentSqlDao.class);
                    invoicePaymentSqlDao.batchCreateFromTransaction(invoicePayments, context);
                    recordIdList = invoicePaymentSqlDao.getRecordIds(invoice.getId().toString());
                    audits.addAll(createAudits(TableName.INVOICE_PAYMENTS, recordIdList));

                    transactional.insertAuditFromTransaction(audits, context);
                }
                return null;
            }
        });
    }

    private List<EntityAudit> createAudits(final TableName tableName, final List<Long> recordIdList) {
        final List<EntityAudit> entityAuditList = new ArrayList<EntityAudit>();
        for (final Long recordId : recordIdList) {
            entityAuditList.add(new EntityAudit(tableName, recordId, ChangeType.INSERT));
        }

        return entityAuditList;
    }

    @Override
    public List<Invoice> getInvoicesBySubscription(final UUID subscriptionId) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {
                final List<Invoice> invoices = invoiceDao.getInvoicesBySubscription(subscriptionId.toString());

                populateChildren(invoices, invoiceDao);

                return invoices;
            }
        });
    }

    @Override
    public BigDecimal getAccountBalance(final UUID accountId) {

        return invoiceSqlDao.inTransaction(new Transaction<BigDecimal, InvoiceSqlDao>() {
            @Override
            public BigDecimal inTransaction(final InvoiceSqlDao transactional, final TransactionStatus status) throws Exception {
                BigDecimal cba = BigDecimal.ZERO;

                BigDecimal accountBalance = BigDecimal.ZERO;
                final List<Invoice> invoices = getAllInvoicesByAccountFromTransaction(accountId, transactional);
                for (final Invoice cur : invoices) {
                    accountBalance = accountBalance.add(cur.getBalance());
                    cba = cba.add(cur.getCBAAmount());
                }
                return accountBalance.subtract(cba);
            }
        });
    }

    @Override
    public BigDecimal getAccountCBA(final UUID accountId) {
        return invoiceSqlDao.inTransaction(new Transaction<BigDecimal, InvoiceSqlDao>() {
            @Override
            public BigDecimal inTransaction(final InvoiceSqlDao transactional, final TransactionStatus status) throws Exception {
                return getAccountCBAFromTransaction(accountId, transactional);
            }
        });
    }

    @Override
    public void notifyOfPayment(final InvoicePayment invoicePayment, final CallContext context) {
        invoicePaymentSqlDao.inTransaction(new Transaction<Void, InvoicePaymentSqlDao>() {
            @Override
            public Void inTransaction(final InvoicePaymentSqlDao transactional, final TransactionStatus status) throws Exception {
                transactional.notifyOfPayment(invoicePayment, context);

                final String invoicePaymentId = invoicePayment.getId().toString();
                final Long recordId = transactional.getRecordId(invoicePaymentId);
                final EntityAudit audit = new EntityAudit(TableName.INVOICE_PAYMENTS, recordId, ChangeType.INSERT);
                transactional.insertAuditFromTransaction(audit, context);

                return null;
            }
        });
    }

    @Override
    public List<Invoice> getUnpaidInvoicesByAccountId(final UUID accountId, @Nullable final LocalDate upToDate) {
        return invoiceSqlDao.inTransaction(new Transaction<List<Invoice>, InvoiceSqlDao>() {
            @Override
            public List<Invoice> inTransaction(final InvoiceSqlDao invoiceDao, final TransactionStatus status) throws Exception {

                final List<Invoice> invoices = getAllInvoicesByAccountFromTransaction(accountId, invoiceDao);
                final Collection<Invoice> unpaidInvoices = Collections2.filter(invoices, new Predicate<Invoice>() {
                    @Override
                    public boolean apply(final Invoice in) {
                        return (in.getBalance().compareTo(BigDecimal.ZERO) >= 1) && (upToDate == null || !in.getTargetDate().isAfter(upToDate));
                    }
                });
                return new ArrayList<Invoice>(unpaidInvoices);
            }
        });
    }

    @Override
    public UUID getInvoiceIdByPaymentId(final UUID paymentId) {
        return invoiceSqlDao.getInvoiceIdByPaymentId(paymentId.toString());
    }

    @Override
    public InvoicePayment getInvoicePayment(final UUID paymentId) {
        return invoicePaymentSqlDao.getInvoicePayment(paymentId.toString());
    }

    @Override
    public void setWrittenOff(final UUID invoiceId, final CallContext context) throws TagApiException {
        tagUserApi.addTag(invoiceId, ObjectType.INVOICE, ControlTagType.WRITTEN_OFF.getId(), context);
    }

    @Override
    public void removeWrittenOff(final UUID invoiceId, final CallContext context) throws TagApiException {
        tagUserApi.removeTag(invoiceId, ObjectType.INVOICE, ControlTagType.WRITTEN_OFF.getId(), context);
    }

    @Override
    public InvoicePayment createRefund(final UUID paymentId, final BigDecimal amount, final boolean isInvoiceAdjusted, final UUID paymentCookieId, final CallContext context)
            throws InvoiceApiException {
        return invoicePaymentSqlDao.inTransaction(new Transaction<InvoicePayment, InvoicePaymentSqlDao>() {
            @Override
            public InvoicePayment inTransaction(final InvoicePaymentSqlDao transactional, final TransactionStatus status) throws Exception {

                final InvoicePayment payment = transactional.getByPaymentId(paymentId.toString());
                if (payment == null) {
                    throw new InvoiceApiException(ErrorCode.INVOICE_PAYMENT_BY_ATTEMPT_NOT_FOUND, paymentId);
                }
                final BigDecimal maxRefundAmount = payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount();
                final BigDecimal requestedPositiveAmount = amount == null ? maxRefundAmount : amount;
                // This check is good but not enough, we need to also take into account previous refunds
                // (But that should have been checked in the payment call already)
                if (requestedPositiveAmount.compareTo(maxRefundAmount) > 0) {
                    throw new InvoiceApiException(ErrorCode.REFUND_AMOUNT_TOO_HIGH, requestedPositiveAmount, maxRefundAmount);
                }

                // Before we go further, check if that refund already got inserted -- the payment system keps a state machine
                // and so this call may be called several time for the same  paymentCookieId (which is really the refundId)
                //
                final InvoicePayment existingRefund = transactional.getPaymentsForCookieId(paymentCookieId.toString());
                if (existingRefund != null) {
                    return existingRefund;
                }

                final InvoicePayment refund = new DefaultInvoicePayment(UUID.randomUUID(), InvoicePaymentType.REFUND, paymentId,
                                                                        payment.getInvoiceId(), context.getCreatedDate(), requestedPositiveAmount.negate(),
                                                                        payment.getCurrency(), paymentCookieId, payment.getId());
                transactional.create(refund, context);

                // Retrieve invoice after the Refund
                final InvoiceSqlDao transInvoiceDao = transactional.become(InvoiceSqlDao.class);
                final Invoice invoice = transInvoiceDao.getById(payment.getInvoiceId().toString());
                if (invoice != null) {
                    populateChildren(invoice, transInvoiceDao);
                } else {
                    throw new IllegalStateException("Invoice shouldn't be null for payment " + payment.getId());
                }

                final BigDecimal invoiceBalanceAfterRefund = invoice.getBalance();
                final InvoiceItemSqlDao transInvoiceItemDao = transInvoiceDao.become(InvoiceItemSqlDao.class);

                // If we have an existing CBA > 0, we need to adjust it
                //final BigDecimal cbaAmountAfterRefund = invoice.getCBAAmount();
                final BigDecimal accountCbaAvailable = getAccountCBAFromTransaction(invoice.getAccountId(), transInvoiceDao);
                BigDecimal cbaAdjAmount = BigDecimal.ZERO;
                if (accountCbaAvailable.compareTo(BigDecimal.ZERO) > 0) {
                    cbaAdjAmount = (requestedPositiveAmount.compareTo(accountCbaAvailable) > 0) ? accountCbaAvailable.negate() : requestedPositiveAmount.negate();
                    final InvoiceItem cbaAdjItem = new CreditBalanceAdjInvoiceItem(invoice.getId(), invoice.getAccountId(), context.getCreatedDate().toLocalDate(), cbaAdjAmount, invoice.getCurrency());
                    transInvoiceItemDao.create(cbaAdjItem, context);
                }
                final BigDecimal requestedPositiveAmountAfterCbaAdj = requestedPositiveAmount.add(cbaAdjAmount);

                if (isInvoiceAdjusted) {
                    final BigDecimal maxBalanceToAdjust = (invoiceBalanceAfterRefund.compareTo(BigDecimal.ZERO) <= 0) ? BigDecimal.ZERO : invoiceBalanceAfterRefund;
                    final BigDecimal requestedPositiveAmountToAdjust = requestedPositiveAmountAfterCbaAdj.compareTo(maxBalanceToAdjust) > 0 ? maxBalanceToAdjust : requestedPositiveAmountAfterCbaAdj;
                    if (requestedPositiveAmountToAdjust.compareTo(BigDecimal.ZERO) > 0) {
                        final InvoiceItem adjItem = new RefundAdjInvoiceItem(invoice.getId(), invoice.getAccountId(), context.getCreatedDate().toLocalDate(), requestedPositiveAmountToAdjust.negate(), invoice.getCurrency());
                        transInvoiceItemDao.create(adjItem, context);
                    }
                }
                return refund;
            }
        });
    }

    @Override
    public InvoicePayment postChargeback(final UUID invoicePaymentId, final BigDecimal amount, final CallContext context) throws InvoiceApiException {

        return invoicePaymentSqlDao.inTransaction(new Transaction<InvoicePayment, InvoicePaymentSqlDao>() {
            @Override
            public InvoicePayment inTransaction(final InvoicePaymentSqlDao transactional, final TransactionStatus status) throws Exception {
                final BigDecimal maxChargedBackAmount = getRemainingAmountPaidFromTransaction(invoicePaymentId, transactional);
                final BigDecimal requestedChargedBackAmout = (amount == null) ? maxChargedBackAmount : amount;
                if (requestedChargedBackAmout.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new InvoiceApiException(ErrorCode.CHARGE_BACK_AMOUNT_IS_NEGATIVE);
                }
                if (requestedChargedBackAmout.compareTo(maxChargedBackAmount) > 0) {
                    throw new InvoiceApiException(ErrorCode.CHARGE_BACK_AMOUNT_TOO_HIGH, requestedChargedBackAmout, maxChargedBackAmount);
                }

                final InvoicePayment payment = invoicePaymentSqlDao.getById(invoicePaymentId.toString());
                if (payment == null) {
                    throw new InvoiceApiException(ErrorCode.INVOICE_PAYMENT_NOT_FOUND, invoicePaymentId.toString());
                } else {
                    final InvoicePayment chargeBack = new DefaultInvoicePayment(UUID.randomUUID(), InvoicePaymentType.CHARGED_BACK, null,
                                                                                payment.getInvoiceId(), context.getCreatedDate(), requestedChargedBackAmout.negate(), payment.getCurrency(), null, payment.getId());
                    invoicePaymentSqlDao.create(chargeBack, context);
                    return chargeBack;
                }
            }
        });
    }

    @Override
    public BigDecimal getRemainingAmountPaid(final UUID invoicePaymentId) {
        return getRemainingAmountPaidFromTransaction(invoicePaymentId, invoicePaymentSqlDao);
    }

    @Override
    public UUID getAccountIdFromInvoicePaymentId(final UUID invoicePaymentId) throws InvoiceApiException {
        final UUID accountId = invoicePaymentSqlDao.getAccountIdFromInvoicePaymentId(invoicePaymentId.toString());
        if (accountId == null) {
            throw new InvoiceApiException(ErrorCode.CHARGE_BACK_COULD_NOT_FIND_ACCOUNT_ID, invoicePaymentId);
        } else {
            return accountId;
        }
    }

    @Override
    public List<InvoicePayment> getChargebacksByAccountId(final UUID accountId) {
        return invoicePaymentSqlDao.getChargeBacksByAccountId(accountId.toString());
    }

    @Override
    public List<InvoicePayment> getChargebacksByPaymentId(final UUID paymentId) {
        return invoicePaymentSqlDao.getChargebacksByPaymentId(paymentId.toString());
    }

    @Override
    public InvoicePayment getChargebackById(final UUID chargebackId) throws InvoiceApiException {
        final InvoicePayment chargeback = invoicePaymentSqlDao.getById(chargebackId.toString());
        if (chargeback == null) {
            throw new InvoiceApiException(ErrorCode.CHARGE_BACK_DOES_NOT_EXIST, chargebackId);
        } else {
            return chargeback;
        }
    }

    @Override
    public InvoiceItem getCreditById(final UUID creditId) throws InvoiceApiException {
        return invoiceItemSqlDao.getById(creditId.toString());
    }

    @Override
    public InvoiceItem insertCredit(final UUID accountId, final UUID invoiceId, final BigDecimal positiveCreditAmount,
                                    final LocalDate effectiveDate, final Currency currency,
                                    final CallContext context) {
        return invoiceSqlDao.inTransaction(new Transaction<InvoiceItem, InvoiceSqlDao>() {
            @Override
            public InvoiceItem inTransaction(final InvoiceSqlDao transactional, final TransactionStatus status) throws Exception {
                UUID invoiceIdForRefund = invoiceId;
                if (invoiceIdForRefund == null) {
                    final Invoice invoiceForRefund = new DefaultInvoice(accountId, effectiveDate, effectiveDate, currency);
                    transactional.create(invoiceForRefund, context);
                    invoiceIdForRefund = invoiceForRefund.getId();
                }

                final InvoiceItem credit = new CreditAdjInvoiceItem(invoiceIdForRefund, accountId, effectiveDate, positiveCreditAmount.negate(), currency);
                final InvoiceItemSqlDao transInvoiceItemDao = transactional.become(InvoiceItemSqlDao.class);
                transInvoiceItemDao.create(credit, context);

                final Invoice invoice = transactional.getById(invoiceIdForRefund.toString());
                if (invoice != null) {
                    populateChildren(invoice, transactional);
                } else {
                    throw new IllegalStateException("Invoice shouldn't be null for credit at this stage " + invoiceIdForRefund);
                }
                // If invoice balance becomes negative we add some CBA item
                if (invoice.getBalance().compareTo(BigDecimal.ZERO) < 0) {
                    final InvoiceItem cbaAdjItem = new CreditBalanceAdjInvoiceItem(invoice.getId(), invoice.getAccountId(), context.getCreatedDate().toLocalDate(),
                                                                                   invoice.getBalance().negate(), invoice.getCurrency());
                    transInvoiceItemDao.create(cbaAdjItem, context);

                }
                return credit;
            }
        });
    }

    @Override
    public void test() {
        invoiceSqlDao.test();
    }

    private BigDecimal getAccountCBAFromTransaction(final UUID accountId, final InvoiceSqlDao transactional) {
        BigDecimal cba = BigDecimal.ZERO;
        final List<Invoice> invoices = getAllInvoicesByAccountFromTransaction(accountId, transactional);
        for (final Invoice cur : invoices) {
            cba = cba.add(cur.getCBAAmount());
        }

        return cba;
    }

    private void populateChildren(final Invoice invoice, final InvoiceSqlDao invoiceSqlDao) {
        getInvoiceItemsWithinTransaction(invoice, invoiceSqlDao);
        getInvoicePaymentsWithinTransaction(invoice, invoiceSqlDao);
    }

    private void populateChildren(final List<Invoice> invoices, final InvoiceSqlDao invoiceSqlDao) {
        getInvoiceItemsWithinTransaction(invoices, invoiceSqlDao);
        getInvoicePaymentsWithinTransaction(invoices, invoiceSqlDao);
    }

    private List<Invoice> getAllInvoicesByAccountFromTransaction(final UUID accountId, final InvoiceSqlDao transactional) {
        final List<Invoice> invoices = transactional.getAllInvoicesByAccount(accountId.toString());
        populateChildren(invoices, transactional);
        return invoices;
    }

    private BigDecimal getRemainingAmountPaidFromTransaction(final UUID invoicePaymentId, final InvoicePaymentSqlDao transactional) {
        final BigDecimal amount = transactional.getRemainingAmountPaid(invoicePaymentId.toString());
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private void getInvoiceItemsWithinTransaction(final List<Invoice> invoices, final InvoiceSqlDao invoiceDao) {
        for (final Invoice invoice : invoices) {
            getInvoiceItemsWithinTransaction(invoice, invoiceDao);
        }
    }

    private void getInvoiceItemsWithinTransaction(final Invoice invoice, final InvoiceSqlDao transactional) {
        final String invoiceId = invoice.getId().toString();

        final InvoiceItemSqlDao transInvoiceItemSqlDao = transactional.become(InvoiceItemSqlDao.class);
        final List<InvoiceItem> items = transInvoiceItemSqlDao.getInvoiceItemsByInvoice(invoiceId);
        invoice.addInvoiceItems(items);
    }

    private void getInvoicePaymentsWithinTransaction(final List<Invoice> invoices, final InvoiceSqlDao invoiceDao) {
        for (final Invoice invoice : invoices) {
            getInvoicePaymentsWithinTransaction(invoice, invoiceDao);
        }
    }

    private void getInvoicePaymentsWithinTransaction(final Invoice invoice, final InvoiceSqlDao invoiceSqlDao) {
        final InvoicePaymentSqlDao invoicePaymentSqlDao = invoiceSqlDao.become(InvoicePaymentSqlDao.class);
        final String invoiceId = invoice.getId().toString();
        final List<InvoicePayment> invoicePayments = invoicePaymentSqlDao.getPaymentsForInvoice(invoiceId);
        invoice.addPayments(invoicePayments);
    }

    private void notifyOfFutureBillingEvents(final InvoiceSqlDao dao, final UUID accountId, final int billCycleDayUTC, final List<InvoiceItem> invoiceItems) {
        UUID subscriptionForNextNotification = null;
        boolean shouldBeNotified = false;
        for (final InvoiceItem item : invoiceItems) {
            if (item.getInvoiceItemType() == InvoiceItemType.RECURRING) {
                final RecurringInvoiceItem recurringInvoiceItem = (RecurringInvoiceItem) item;
                if ((recurringInvoiceItem.getEndDate() != null) &&
                    (recurringInvoiceItem.getAmount() == null ||
                     recurringInvoiceItem.getAmount().compareTo(BigDecimal.ZERO) >= 0)) {
                    subscriptionForNextNotification = recurringInvoiceItem.getSubscriptionId();
                    shouldBeNotified = true;
                    break;
                }
            }
        }

        // We only need to get notified on the BCD. For other invoice events (e.g. phase changes),
        // we'll be notified by entitlement.
        if (shouldBeNotified) {
            // We could be notified at any time during the day at the billCycleDay - use the current time to
            // spread the load.
            final DateTime nextNotificationDateTime = InvoiceDateUtils.calculateBillingCycleDateAfter(clock.getUTCNow(), billCycleDayUTC);
            // NextBillingDatePoster will ignore duplicates
            nextBillingDatePoster.insertNextBillingNotification(dao, accountId, subscriptionForNextNotification, nextNotificationDateTime);
        }
    }
}
