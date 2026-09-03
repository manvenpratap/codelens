#!/usr/bin/env python3
"""
Enterprise Java Codebase Generator for CodeLens Load Testing.
Generates 650+ realistic Java classes, interfaces, enums, and records across 12 TCS BaNCS banking domains:
  - AM: Account Management & Demand Deposits
  - LN: Loans & Credit Facilities
  - TR: Trading & Capital Markets Execution
  - CL: Clearing & Multilateral Settlement
  - RK: Enterprise Risk & Compliance
  - MS: Financial Messaging & Protocols
  - AN: Quantitative Analytics & Regulatory Reporting
  - DP: Term Deposits & Structured Products
  - GL: General Ledger & Double-Entry Accounting
  - PM: Payment Systems & Real-Time Gross Settlement
  - CU: Customer Information File & KYC / AML
  - SC: Security Pledges & Collateral Management
  - common: Enterprise Framework & Audit Infrastructure
"""

import os
import sys
from pathlib import Path

# domain_data.py
"""Complete metadata definitions for 12 TCS BaNCS domains + common module."""

MODULES = {
    "AM": {
        "name": "Account Management & Demand Deposits",
        "cross_deps": ["GL", "CU", "common"],
        "persistent_classes": [
            {
                "name": "Account",
                "id_field": "accountNumber",
                "fields": [
                    ("String", "accountNumber"), ("String", "customerId"), ("String", "accountType"),
                    ("double", "availableBalance"), ("double", "ledgerBalance"), ("double", "creditLimit"),
                    ("double", "interestRate"), ("double", "holdAmount"), ("String", "status"),
                    ("long", "lastTransactionTimestamp")
                ],
                "business_methods": [
                    ("postDebit", [("double", "amount"), ("String", "narration")], "availableBalance = availableBalance - amount; ledgerBalance = ledgerBalance - amount; lastTransactionTimestamp = System.currentTimeMillis();"),
                    ("postCredit", [("double", "amount"), ("String", "narration")], "availableBalance = availableBalance + amount; ledgerBalance = ledgerBalance + amount; lastTransactionTimestamp = System.currentTimeMillis();"),
                    ("blockHold", [("String", "holdId"), ("double", "amount")], "availableBalance = availableBalance - amount; holdAmount = holdAmount + amount; lastTransactionTimestamp = System.currentTimeMillis();"),
                    ("releaseHold", [("String", "holdId"), ("double", "amount")], "availableBalance = availableBalance + amount; holdAmount = Math.max(0.0, holdAmount - amount); lastTransactionTimestamp = System.currentTimeMillis();"),
                    ("accrueDailyInterest", [("double", "dailyRate")], "double accrued = (availableBalance * dailyRate) / 365.0; ledgerBalance = ledgerBalance + accrued; lastTransactionTimestamp = System.currentTimeMillis();")
                ]
            },
            {
                "name": "AccountLimit",
                "id_field": "limitId",
                "fields": [
                    ("String", "limitId"), ("String", "accountNumber"), ("double", "sanctionedLimit"),
                    ("double", "drawingPower"), ("double", "utilizedLimit"), ("String", "expiryDate"),
                    ("String", "limitStatus")
                ],
                "business_methods": [
                    ("utilize", [("double", "amount")], "utilizedLimit = utilizedLimit + amount; drawingPower = Math.max(0.0, sanctionedLimit - utilizedLimit);"),
                    ("reinstate", [("double", "amount")], "utilizedLimit = Math.max(0.0, utilizedLimit - amount); drawingPower = Math.max(0.0, sanctionedLimit - utilizedLimit);"),
                    ("renewExpiry", [("String", "newDate")], "expiryDate = newDate; limitStatus = \"ACTIVE\";")
                ]
            },
            {
                "name": "OverdraftFacility",
                "id_field": "facilityId",
                "fields": [
                    ("String", "facilityId"), ("String", "accountNumber"), ("double", "overdraftLimit"),
                    ("double", "penalRate"), ("String", "startDate"), ("String", "reviewDate"), ("boolean", "isActive")
                ],
                "business_methods": [
                    ("activateFacility", [("double", "limit"), ("double", "rate")], "overdraftLimit = limit; penalRate = rate; isActive = true;"),
                    ("suspendFacility", [("String", "reason")], "isActive = false; reviewDate = reason;"),
                    ("updateRate", [("double", "newRate")], "penalRate = newRate;")
                ]
            },
            {
                "name": "AccountFeeSchedule",
                "id_field": "scheduleId",
                "fields": [
                    ("String", "scheduleId"), ("String", "accountType"), ("double", "monthlyFee"),
                    ("double", "transactionCharge"), ("double", "minBalanceThreshold"), ("String", "waiverCode")
                ],
                "business_methods": [
                    ("applyWaiver", [("String", "code")], "waiverCode = code; monthlyFee = 0.0;"),
                    ("updateCharges", [("double", "fee"), ("double", "charge")], "monthlyFee = fee; transactionCharge = charge;")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_AccountOpen", [("String", "customerId"), ("String", "accountType"), ("String", "currency"), ("double", "initialDeposit"), ("String", "branchCode")]),
            ("MO_OUT_AccountOpen", [("String", "accountNumber"), ("String", "status"), ("String", "responseCode"), ("double", "initialBalance"), ("long", "openTimestamp")]),
            ("MO_INP_FundTransfer", [("String", "sourceAccount"), ("String", "destinationAccount"), ("double", "amount"), ("String", "currency"), ("String", "narration")]),
            ("MO_OUT_FundTransfer", [("String", "referenceId"), ("String", "status"), ("double", "sourceNewBalance"), ("double", "feeCharged"), ("long", "transferTime")]),
            ("MO_INP_BalanceInquiry", [("String", "accountNumber"), ("boolean", "includeHolds"), ("String", "channel")]),
            ("MO_OUT_BalanceInquiry", [("String", "accountNumber"), ("double", "availableBalance"), ("double", "ledgerBalance"), ("double", "holdAmount"), ("String", "status")]),
            ("MO_INP_AccountClosure", [("String", "accountNumber"), ("String", "reason"), ("String", "payoutAccount"), ("boolean", "waiveCharges")]),
            ("MO_OUT_AccountClosure", [("String", "accountNumber"), ("String", "status"), ("double", "finalSettlementAmount"), ("long", "closureTimestamp")]),
            ("MO_INP_HoldFunds", [("String", "accountNumber"), ("double", "amount"), ("String", "reason"), ("String", "expiryDate")]),
            ("MO_OUT_HoldFunds", [("String", "holdReferenceId"), ("String", "accountNumber"), ("double", "amountHeld"), ("String", "status")]),
            ("MO_AccountSummary", [("String", "accountNumber"), ("String", "customerId"), ("String", "accountType"), ("double", "balance"), ("String", "status")]),
            ("MO_PostingLeg", [("String", "accountNumber"), ("String", "entrySide"), ("double", "amount"), ("String", "glCode"), ("String", "narrative")])
        ],
        "data_grabbers": ["AMDGAccountGrabber", "AMDGBalanceGrabber", "AMDGLimitGrabber", "AMDGStatementGrabber"],
        "business_transactions": ["AMBTAccountOpen", "AMBTFundTransfer", "AMBTCloseAccount", "AMBTApplyCharge", "AMBTHoldFunds"],
        "elementary_transactions": ["AMETFetchBalance", "AMETQueryAccountDetails", "AMETSearchStatements", "AMETValidateAccount"],
        "batch_processors": ["AMPSDailyAccrual", "AMPBPreAccrualValidation", "AMPAPostAccrualReconcile"],
        "services": ["AccountService", "InterestCalculationService", "FeeAssessmentService", "AccountLifecycleService", "StatementGenerationService", "HoldFundsService"],
        "controllers": ["AccountController", "FundTransferController", "AccountAdminController"],
        "enums": [
            ("AccountType", ["SAVINGS", "CURRENT", "CORPORATE", "ESCROW", "NOSTRO"]),
            ("AccountStatus", ["ACTIVE", "DORMANT", "FROZEN", "CLOSED", "UNDER_AUDIT"]),
            ("HoldReason", ["COURT_ORDER", "SUSPECTED_FRAUD", "MARGIN_PLEDGE", "PENDING_CLEARING", "TAX_LEVY"]),
            ("AccrualMethod", ["SIMPLE_DAILY", "MONTHLY_COMPOUND", "QUARTERLY_AVERAGE", "YEAR_END"]),
            ("PostingChannel", ["BRANCH", "INTERNET_BANKING", "MOBILE_APP", "ATM", "OPEN_API"])
        ],
        "records": [
            ("AccountSnapshotRecord", [("String", "accountNumber"), ("double", "balance"), ("long", "snapshotTimestamp")]),
            ("BalanceAuditRecord", [("String", "accountNumber"), ("double", "priorBalance"), ("double", "newBalance"), ("String", "reason")]),
            ("InterestPostingRecord", [("String", "accountNumber"), ("double", "accruedInterest"), ("String", "period")]),
            ("FeeScheduleRecord", [("String", "scheduleId"), ("String", "accountType"), ("double", "feeAmount")])
        ],
        "interfaces": [
            ("AccountOperations", ["boolean executeDeposit(String acc, double amt)", "boolean executeWithdrawal(String acc, double amt)", "double queryBalance(String acc)"]),
            ("InterestCalculator", ["double computeAccrual(double balance, double rate, int days)", "double computeOverdraftPenalty(double negativeBalance, double penaltyRate)"]),
            ("BalanceObserver", ["void onBalanceChanged(String accountNumber, double oldBal, double newBal)"])
        ]
    },

    "LN": {
        "name": "Loans & Credit Facilities",
        "cross_deps": ["AM", "GL", "CU", "SC", "RK", "common"],
        "persistent_classes": [
            {
                "name": "Loan",
                "id_field": "loanId",
                "fields": [
                    ("String", "loanId"), ("String", "customerId"), ("String", "loanType"),
                    ("double", "sanctionedPrincipal"), ("double", "disbursedAmount"), ("double", "outstandingBalance"),
                    ("double", "interestRate"), ("int", "tenureMonths"), ("double", "emiAmount"),
                    ("String", "loanStatus"), ("String", "npaCategory"), ("String", "nextPaymentDueDate")
                ],
                "business_methods": [
                    ("disburse", [("double", "amount")], "disbursedAmount = disbursedAmount + amount; outstandingBalance = outstandingBalance + amount; loanStatus = \"ACTIVE\";"),
                    ("applyRepayment", [("double", "principalPart"), ("double", "interestPart")], "outstandingBalance = Math.max(0.0, outstandingBalance - principalPart);"),
                    ("recalculateEmi", [("double", "newRate"), ("int", "remainingTenure")], "interestRate = newRate; tenureMonths = remainingTenure; emiAmount = (outstandingBalance * (1 + newRate/100.0)) / Math.max(1, remainingTenure);"),
                    ("markDelinquent", [("String", "category")], "npaCategory = category; loanStatus = \"DELINQUENT\";"),
                    ("restructure", [("int", "extraTenure"), ("double", "concessionalRate")], "tenureMonths = tenureMonths + extraTenure; interestRate = concessionalRate;")
                ]
            },
            {
                "name": "LoanRepaymentSchedule",
                "id_field": "scheduleId",
                "fields": [
                    ("String", "scheduleId"), ("String", "loanId"), ("int", "installmentNumber"),
                    ("String", "dueDate"), ("double", "principalComponent"), ("double", "interestComponent"),
                    ("double", "feeComponent"), ("String", "paymentStatus")
                ],
                "business_methods": [
                    ("markPaid", [("String", "paidDate")], "paymentStatus = \"PAID\"; dueDate = paidDate;"),
                    ("reschedule", [("String", "newDueDate")], "dueDate = newDueDate; paymentStatus = \"RESCHEDULED\";")
                ]
            },
            {
                "name": "LoanDisbursementTranche",
                "id_field": "trancheId",
                "fields": [
                    ("String", "trancheId"), ("String", "loanId"), ("double", "trancheAmount"),
                    ("String", "targetDisbursementDate"), ("String", "disbursementStatus"), ("String", "referenceId")
                ],
                "business_methods": [
                    ("executeDisbursement", [("String", "ref")], "referenceId = ref; disbursementStatus = \"COMPLETED\";"),
                    ("cancelTranche", [("String", "reason")], "disbursementStatus = \"CANCELLED\"; referenceId = reason;")
                ]
            },
            {
                "name": "DelinquencyRecord",
                "id_field": "delinquencyId",
                "fields": [
                    ("String", "delinquencyId"), ("String", "loanId"), ("int", "daysPastDue"),
                    ("double", "overduePrincipal"), ("double", "overdueInterest"), ("double", "penaltyCharged"),
                    ("String", "recoveryStage")
                ],
                "business_methods": [
                    ("escalateNPA", [("String", "stage")], "recoveryStage = stage; daysPastDue = daysPastDue + 30;"),
                    ("settleDues", [("double", "settledAmount")], "overduePrincipal = 0.0; overdueInterest = 0.0; penaltyCharged = 0.0; recoveryStage = \"RESOLVED\";")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_LoanApplication", [("String", "customerId"), ("String", "loanType"), ("double", "requestedAmount"), ("int", "tenureMonths"), ("String", "purpose")]),
            ("MO_OUT_LoanApplication", [("String", "loanId"), ("String", "status"), ("double", "approvedAmount"), ("double", "assignedRate"), ("double", "estimatedEmi")]),
            ("MO_INP_LoanDisbursement", [("String", "loanId"), ("double", "disbursementAmount"), ("String", "creditAccountNumber"), ("String", "trancheNumber")]),
            ("MO_OUT_LoanDisbursement", [("String", "disbursementRef"), ("String", "loanId"), ("String", "status"), ("double", "amountDisbursed"), ("long", "timestamp")]),
            ("MO_INP_LoanRepayment", [("String", "loanId"), ("double", "paymentAmount"), ("String", "debitAccountNumber"), ("String", "paymentMode")]),
            ("MO_OUT_LoanRepayment", [("String", "receiptNumber"), ("String", "loanId"), ("double", "principalPaid"), ("double", "interestPaid"), ("double", "remainingBalance")]),
            ("MO_INP_ScheduleRestructure", [("String", "loanId"), ("int", "additionalMonths"), ("double", "proposedRate"), ("String", "restructureReason")]),
            ("MO_OUT_ScheduleRestructure", [("String", "loanId"), ("String", "status"), ("double", "newEmi"), ("int", "newTotalTenure")]),
            ("MO_INP_ForeclosureQuote", [("String", "loanId"), ("String", "intendedDate")]),
            ("MO_OUT_ForeclosureQuote", [("String", "loanId"), ("double", "principalOutstanding"), ("double", "interestTillDate"), ("double", "foreclosurePenalty"), ("double", "totalSettlement")]),
            ("MO_LoanAccountSummary", [("String", "loanId"), ("String", "customerId"), ("double", "outstandingBalance"), ("String", "npaCategory"), ("String", "status")]),
            ("MO_AmortizationInstallment", [("int", "periodNumber"), ("String", "dueDate"), ("double", "installmentAmount"), ("double", "principal"), ("double", "interest")])
        ],
        "data_grabbers": ["LNDGLoanGrabber", "LNDGScheduleGrabber", "LNDGDelinquencyGrabber", "LNDGDisbursementGrabber"],
        "business_transactions": ["LNBTLoanOrigination", "LNBTDisburseLoan", "LNBTProcessRepayment", "LNBTForecloseLoan", "LNBTRestructureSchedule"],
        "elementary_transactions": ["LNETQuerySchedule", "LNETGetLoanSummary", "LNETCalculateForeclosure", "LNETCheckDelinquencyStatus"],
        "batch_processors": ["LNPSInstallmentDueBatch", "LNPBPreDueValidation", "LNPAPostPaymentReconcile"],
        "services": ["LoanOriginationService", "AmortizationCalculationService", "RepaymentService", "DelinquencyTrackingService", "InterestRebateService", "LoanCollateralLinkService"],
        "controllers": ["LoanController", "LoanServicingController", "LoanScheduleController"],
        "enums": [
            ("LoanType", ["HOME_LOAN", "AUTO_LOAN", "PERSONAL_LOAN", "COMMERCIAL_MORTGAGE", "SYNDICATED_LOAN"]),
            ("LoanStatus", ["APPLIED", "SANCTIONED", "ACTIVE", "DELINQUENT", "CLOSED", "WRITTEN_OFF"]),
            ("AmortizationType", ["EQUAL_MONTHLY_INSTALLMENT", "BALLOON_PAYMENT", "INTEREST_ONLY", "STEP_UP_REPAYMENT"]),
            ("NPACategory", ["STANDARD", "SPECIAL_MENTION", "SUB_STANDARD", "DOUBTFUL", "LOSS_ASSET"]),
            ("DisbursementStatus", ["PENDING", "APPROVED", "DISBURSED", "REJECTED", "CANCELLED"])
        ],
        "records": [
            ("AmortizationSliceRecord", [("int", "month"), ("double", "principal"), ("double", "interest"), ("double", "balance")]),
            ("RepaymentReceiptRecord", [("String", "receiptId"), ("String", "loanId"), ("double", "amount"), ("long", "timestamp")]),
            ("NPATransitionRecord", [("String", "loanId"), ("String", "previousNPA"), ("String", "newNPA"), ("int", "dpd")]),
            ("DisbursementTrancheRecord", [("String", "trancheId"), ("String", "loanId"), ("double", "amount")])
        ],
        "interfaces": [
            ("LoanLifecycleManager", ["boolean approveLoan(String loanId)", "boolean disburseTranche(String loanId, double amt)", "boolean closeLoan(String loanId)"]),
            ("AmortizationEngine", ["List<MO_AmortizationInstallment> generateSchedule(double principal, double rate, int months)"]),
            ("DelinquencyObserver", ["void onDelinquencyTransition(String loanId, String oldStage, String newStage)"])
        ]
    },

    "TR": {
        "name": "Trading & Capital Markets Execution",
        "cross_deps": ["CL", "RK", "AN", "common"],
        "persistent_classes": [
            {
                "name": "OrderEntity",
                "id_field": "orderId",
                "fields": [
                    ("String", "orderId"), ("String", "portfolioId"), ("String", "symbol"),
                    ("String", "assetClass"), ("String", "orderSide"), ("int", "quantity"),
                    ("double", "limitPrice"), ("double", "stopPrice"), ("int", "executedQty"),
                    ("double", "cumValue"), ("String", "orderStatus"), ("long", "createdTimestamp")
                ],
                "business_methods": [
                    ("appendFill", [("int", "fillQty"), ("double", "fillPrice")], "executedQty = executedQty + fillQty; cumValue = cumValue + (fillQty * fillPrice); orderStatus = (executedQty >= quantity) ? \"FILLED\" : \"PARTIALLY_FILLED\";"),
                    ("markCancelled", [("String", "reason")], "orderStatus = \"CANCELLED\";"),
                    ("rejectOrder", [("String", "code")], "orderStatus = \"REJECTED\";")
                ]
            },
            {
                "name": "TradeExecution",
                "id_field": "executionId",
                "fields": [
                    ("String", "executionId"), ("String", "orderId"), ("String", "executingVenue"),
                    ("double", "executedPrice"), ("int", "executedVolume"), ("double", "commission"),
                    ("long", "executionTimestamp"), ("String", "liquidityFlag")
                ],
                "business_methods": [
                    ("settleExecution", [("double", "fee")], "commission = fee; liquidityFlag = \"SETTLED\";")
                ]
            },
            {
                "name": "PortfolioHolding",
                "id_field": "holdingId",
                "fields": [
                    ("String", "holdingId"), ("String", "portfolioId"), ("String", "symbol"),
                    ("int", "currentQuantity"), ("double", "averageBookPrice"), ("double", "marketValue"),
                    ("double", "unrealizedPnL")
                ],
                "business_methods": [
                    ("adjustPosition", [("int", "deltaQty"), ("double", "tradePrice")], "double totalCost = (currentQuantity * averageBookPrice) + (deltaQty * tradePrice); currentQuantity = currentQuantity + deltaQty; averageBookPrice = (currentQuantity > 0) ? (totalCost / currentQuantity) : 0.0;"),
                    ("markToMarket", [("double", "currentMarketPrice")], "marketValue = currentQuantity * currentMarketPrice; unrealizedPnL = marketValue - (currentQuantity * averageBookPrice);")
                ]
            },
            {
                "name": "TradingStrategyConfig",
                "id_field": "strategyId",
                "fields": [
                    ("String", "strategyId"), ("String", "strategyName"), ("String", "targetAsset"),
                    ("int", "maxOrderSize"), ("double", "riskLimit"), ("boolean", "isActive")
                ],
                "business_methods": [
                    ("toggleStrategy", [("boolean", "active")], "isActive = active;"),
                    ("updateParameters", [("int", "maxSize"), ("double", "limit")], "maxOrderSize = maxSize; riskLimit = limit;")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_OrderSubmission", [("String", "portfolioId"), ("String", "symbol"), ("String", "orderSide"), ("int", "quantity"), ("double", "price"), ("String", "orderType")]),
            ("MO_OUT_OrderSubmission", [("String", "orderId"), ("String", "status"), ("String", "symbol"), ("int", "acceptedQty"), ("long", "ackTimestamp")]),
            ("MO_INP_OrderCancel", [("String", "orderId"), ("String", "reason"), ("String", "operatorId")]),
            ("MO_OUT_OrderCancel", [("String", "orderId"), ("String", "cancelStatus"), ("int", "remainingCancelledQty")]),
            ("MO_INP_TradeAllocation", [("String", "tradeId"), ("String", "subAccountId"), ("int", "allocatedQuantity"), ("double", "allocatedPrice")]),
            ("MO_OUT_TradeAllocation", [("String", "allocationId"), ("String", "tradeId"), ("String", "status")]),
            ("MO_INP_QuoteRequest", [("String", "symbol"), ("int", "size"), ("String", "side")]),
            ("MO_OUT_QuoteRequest", [("String", "quoteId"), ("String", "symbol"), ("double", "bidPrice"), ("double", "askPrice"), ("long", "validUntil")]),
            ("MO_TradeExecutionReport", [("String", "executionId"), ("String", "orderId"), ("String", "symbol"), ("int", "qty"), ("double", "price"), ("String", "side")]),
            ("MO_PortfolioPosition", [("String", "portfolioId"), ("String", "symbol"), ("int", "quantity"), ("double", "marketValue"), ("double", "pnl")]),
            ("MO_MarketDepthSnapshot", [("String", "symbol"), ("double", "bestBid"), ("double", "bestAsk"), ("int", "bidVolume"), ("int", "askVolume")]),
            ("MO_AlgorithmicSignal", [("String", "strategyId"), ("String", "symbol"), ("String", "action"), ("double", "confidence")])
        ],
        "data_grabbers": ["TRDGTradeGrabber", "TRDGOrderBookGrabber", "TRDGPortfolioGrabber", "TRDGMarketQuoteGrabber"],
        "business_transactions": ["TRBTSubmitOrder", "TRBTCancelOrder", "TRBTExecuteTrade", "TRBTAllocatePosition", "TRBTRebalancePortfolio"],
        "elementary_transactions": ["TRETGetOrderStatus", "TRETQueryActiveOrders", "TRETGetPositionSummary", "TRETFetchMarketQuote"],
        "batch_processors": ["TRPSEODPositionMarking", "TRPBPreMarkValidation", "TRPAPostTradeReconcile"],
        "services": ["OrderRoutingService", "OrderValidationService", "ExecutionReportingService", "PositionTrackingService", "AlgorithmicPricingService", "MarketDataFeedService"],
        "controllers": ["TradingDeskController", "OrderManagementController", "PortfolioAllocationController"],
        "enums": [
            ("OrderSide", ["BUY", "SELL", "SELL_SHORT", "BUY_TO_COVER"]),
            ("OrderType", ["MARKET", "LIMIT", "STOP_LIMIT", "TRAILING_STOP", "ICEBERG"]),
            ("TimeInForce", ["DAY", "GTC", "IOC", "FOK", "AT_THE_OPEN"]),
            ("TradeExecutionStatus", ["PENDING", "PARTIALLY_FILLED", "FILLED", "CANCELLED", "REJECTED"]),
            ("AssetClass", ["EQUITIES", "FIXED_INCOME", "FX_SPOT", "COMMODITIES", "RATES_DERIVATIVE"])
        ],
        "records": [
            ("ExecutionSnapshotRecord", [("String", "execId"), ("String", "orderId"), ("double", "price"), ("int", "qty")]),
            ("FillDetailRecord", [("String", "fillId"), ("int", "fillQty"), ("double", "fillPrice"), ("long", "timestamp")]),
            ("OrderAuditEventRecord", [("String", "orderId"), ("String", "action"), ("String", "user")]),
            ("MarketQuoteRecord", [("String", "symbol"), ("double", "bid"), ("double", "ask"), ("long", "time")])
        ],
        "interfaces": [
            ("OrderRouter", ["boolean routeOrder(MO_INP_OrderSubmission req)", "boolean cancelRoutedOrder(String orderId)"]),
            ("MatchingEngineListener", ["void onOrderMatched(String orderId, String tradeId, int qty, double price)"]),
            ("PriceFeedSubscriber", ["void onPriceUpdate(String symbol, double price)"])
        ]
    },

    "CL": {
        "name": "Clearing & Multilateral Settlement",
        "cross_deps": ["GL", "PM", "TR", "common"],
        "persistent_classes": [
            {
                "name": "SettlementInstruction",
                "id_field": "instructionId",
                "fields": [
                    ("String", "instructionId"), ("String", "tradeId"), ("String", "settlementCycle"),
                    ("String", "intendedSettlementDate"), ("String", "actualSettlementDate"), ("String", "deliveringParty"),
                    ("String", "receivingParty"), ("String", "securityIsin"), ("int", "settlementUnits"),
                    ("double", "settlementCashAmount"), ("String", "status")
                ],
                "business_methods": [
                    ("affirmInstruction", [("String", "party")], "status = \"AFFIRMED\"; deliveringParty = party;"),
                    ("matchInstruction", [("String", "counterparty")], "status = \"MATCHED\"; receivingParty = counterparty;"),
                    ("settleInstruction", [("String", "actualDate")], "actualSettlementDate = actualDate; status = \"SETTLED\";"),
                    ("failInstruction", [("String", "reasonCode")], "status = \"FAILED\"; settlementCycle = reasonCode;")
                ]
            },
            {
                "name": "NettingBatch",
                "id_field": "batchId",
                "fields": [
                    ("String", "batchId"), ("String", "batchDate"), ("String", "clearingMemberId"),
                    ("int", "grossTradeCount"), ("double", "netCashObligation"), ("int", "netSecurityObligation"),
                    ("String", "batchStatus")
                ],
                "business_methods": [
                    ("closeBatch", [], "batchStatus = \"CLOSED\";"),
                    ("postObligation", [("double", "cash"), ("int", "units")], "netCashObligation = netCashObligation + cash; netSecurityObligation = netSecurityObligation + units; grossTradeCount = grossTradeCount + 1;")
                ]
            },
            {
                "name": "DepositoryAccount",
                "id_field": "depositoryId",
                "fields": [
                    ("String", "depositoryId"), ("String", "participantCode"), ("String", "isin"),
                    ("int", "settledUnits"), ("int", "blockedUnits"), ("int", "pledgedUnits"), ("String", "lastAuditDate")
                ],
                "business_methods": [
                    ("creditUnits", [("int", "units")], "settledUnits = settledUnits + units;"),
                    ("debitUnits", [("int", "units")], "settledUnits = Math.max(0, settledUnits - units);"),
                    ("pledgeUnits", [("int", "units")], "settledUnits = settledUnits - units; pledgedUnits = pledgedUnits + units;")
                ]
            },
            {
                "name": "SettlementFailRecord",
                "id_field": "failId",
                "fields": [
                    ("String", "failId"), ("String", "instructionId"), ("String", "failReasonCode"),
                    ("double", "penaltyAccrued"), ("int", "curePeriodDays"), ("String", "resolutionStatus")
                ],
                "business_methods": [
                    ("resolveFail", [("String", "note")], "resolutionStatus = \"RESOLVED\"; failReasonCode = note;"),
                    ("accruePenalty", [("double", "penalty")], "penaltyAccrued = penaltyAccrued + penalty;")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_SettlementInstruct", [("String", "tradeId"), ("String", "isin"), ("int", "units"), ("double", "amount"), ("String", "settlementDate")]),
            ("MO_OUT_SettlementInstruct", [("String", "instructionId"), ("String", "status"), ("String", "intendedDate")]),
            ("MO_INP_NettingRequest", [("String", "clearingMemberId"), ("String", "batchDate"), ("String", "cycle")]),
            ("MO_OUT_NettingRequest", [("String", "batchId"), ("double", "netCashAmount"), ("int", "netUnits"), ("String", "status")]),
            ("MO_INP_Affirmation", [("String", "instructionId"), ("String", "affirmingParty"), ("boolean", "isAffirmed")]),
            ("MO_OUT_Affirmation", [("String", "instructionId"), ("String", "matchStatus"), ("long", "timestamp")]),
            ("MO_INP_DepositoryTransfer", [("String", "fromParticipant"), ("String", "toParticipant"), ("String", "isin"), ("int", "units")]),
            ("MO_OUT_DepositoryTransfer", [("String", "transferId"), ("String", "status"), ("int", "unitsTransferred")]),
            ("MO_SettlementObligation", [("String", "memberId"), ("String", "isin"), ("int", "deliverUnits"), ("double", "payCash")]),
            ("MO_ClearingSummary", [("String", "batchId"), ("int", "totalTrades"), ("double", "totalGrossVolume"), ("double", "nettingEfficiency")]),
            ("MO_FailReport", [("String", "failId"), ("String", "instructionId"), ("String", "reason"), ("double", "penalty")]),
            ("MO_DepositoryBalance", [("String", "participant"), ("String", "isin"), ("int", "available"), ("int", "pledged")])
        ],
        "data_grabbers": ["CLDGSettlementGrabber", "CLDGNettingGrabber", "CLDGCustodyGrabber", "CLDGFailGrabber"],
        "business_transactions": ["CLBTSettleInstruction", "CLBTProcessNetting", "CLBTAffirmTrade", "CLBTMarginTransfer", "CLBTResolveSettlementFail"],
        "elementary_transactions": ["CLETCheckSettlementStatus", "CLETQueryNettingObligations", "CLETGetDepositoryHoldings", "CLETQueryFailRecords"],
        "batch_processors": ["CLPSEODSettlementCutoff", "CLPBPreSettlementCheck", "CLPAPostSettlementReconcile"],
        "services": ["ClearingHouseGatewayService", "NettingCalculationService", "CustodyManagementService", "SettlementInstructionService", "FailManagementService", "CollateralEarmarkService"],
        "controllers": ["ClearingController", "SettlementGatewayController", "CustodyController"],
        "enums": [
            ("SettlementStatus", ["PENDING", "AFFIRMED", "MATCHED", "SETTLED", "FAILED", "CANCELLED"]),
            ("SettlementCycle", ["T_PLUS_0", "T_PLUS_1", "T_PLUS_2", "SAME_DAY_RTGS"]),
            ("ClearingModel", ["CENTRAL_COUNTERPARTY", "BILATERAL_GROSS", "NET_PERIODIC"]),
            ("DepositoryType", ["CENTRAL_SECURITIES_DEPOSITORY", "INTERNATIONAL_CSD", "SUB_CUSTODIAN"]),
            ("FailReason", ["SECURITIES_SHORTFALL", "CASH_SHORTFALL", "INSTRUCTION_MISMATCH", "SSI_INVALID"])
        ],
        "records": [
            ("NetObligationRecord", [("String", "memberId"), ("double", "cash"), ("int", "units")]),
            ("InstructionMatchRecord", [("String", "instructionId"), ("boolean", "matched"), ("long", "time")]),
            ("CustodyMovementRecord", [("String", "isin"), ("int", "units"), ("String", "fromParty"), ("String", "toParty")]),
            ("FailResolutionRecord", [("String", "failId"), ("String", "resolution"), ("long", "timestamp")])
        ],
        "interfaces": [
            ("ClearingGateway", ["boolean submitToCCP(String instructionId)", "String queryCCPStatus(String instructionId)"]),
            ("SettlementProcessor", ["boolean executeDVP(String instructionId)", "boolean cancelInstruction(String instructionId)"]),
            ("NettingEngine", ["MO_OUT_NettingRequest computeMultilateralNetting(String clearingMemberId)"])
        ]
    },

    "RK": {
        "name": "Enterprise Risk & Compliance",
        "cross_deps": ["AM", "LN", "TR", "common"],
        "persistent_classes": [
            {
                "name": "RiskExposure",
                "id_field": "exposureId",
                "fields": [
                    ("String", "exposureId"), ("String", "counterpartyId"), ("String", "exposureType"),
                    ("double", "currentExposure"), ("double", "peakExposure"), ("double", "potentialFutureExposure"),
                    ("double", "collateralHeld"), ("double", "netExposure"), ("double", "riskWeight"),
                    ("String", "calculatedDate")
                ],
                "business_methods": [
                    ("updateExposure", [("double", "newVal")], "currentExposure = newVal; peakExposure = Math.max(peakExposure, newVal); netExposure = Math.max(0.0, currentExposure - collateralHeld);"),
                    ("recalculatePFE", [("double", "confidenceMultiplier")], "potentialFutureExposure = currentExposure * confidenceMultiplier;"),
                    ("applyHaircut", [("double", "haircutPct")], "collateralHeld = collateralHeld * (1.0 - haircutPct); netExposure = Math.max(0.0, currentExposure - collateralHeld);")
                ]
            },
            {
                "name": "PartyRiskLimit",
                "id_field": "limitId",
                "fields": [
                    ("String", "limitId"), ("String", "partyId"), ("String", "limitCategory"),
                    ("String", "currency"), ("double", "sanctionedAmount"), ("double", "utilizedAmount"),
                    ("double", "thresholdWarningPct"), ("boolean", "isBlocked")
                ],
                "business_methods": [
                    ("allocate", [("double", "amount")], "utilizedAmount = utilizedAmount + amount; if (utilizedAmount >= sanctionedAmount) isBlocked = true;"),
                    ("deallocate", [("double", "amount")], "utilizedAmount = Math.max(0.0, utilizedAmount - amount); if (utilizedAmount < sanctionedAmount) isBlocked = false;"),
                    ("overrideLimit", [("double", "newSanctioned")], "sanctionedAmount = newSanctioned; isBlocked = false;")
                ]
            },
            {
                "name": "AmlAlertRecord",
                "id_field": "alertId",
                "fields": [
                    ("String", "alertId"), ("String", "transactionId"), ("String", "customerId"),
                    ("String", "ruleTriggered"), ("double", "riskScore"), ("String", "investigationStatus"),
                    ("String", "complianceOfficerId")
                ],
                "business_methods": [
                    ("escalateAlert", [("String", "officerId")], "complianceOfficerId = officerId; investigationStatus = \"ESCALATED\";"),
                    ("clearAlert", [("String", "reason")], "investigationStatus = \"CLEARED\"; ruleTriggered = reason;"),
                    ("fileSAR", [("String", "reportId")], "investigationStatus = \"SAR_FILED\"; ruleTriggered = reportId;")
                ]
            },
            {
                "name": "VaRCalculationResult",
                "id_field": "varId",
                "fields": [
                    ("String", "varId"), ("String", "portfolioId"), ("double", "confidenceInterval"),
                    ("int", "timeHorizonDays"), ("double", "historicalVaR"), ("double", "parametricVaR"),
                    ("double", "monteCarloVaR"), ("long", "calculationTimestamp")
                ],
                "business_methods": [
                    ("backtest", [("double", "actualLoss")], "if (actualLoss > historicalVaR) calculationTimestamp = System.currentTimeMillis();")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_LimitEvaluation", [("String", "partyId"), ("String", "category"), ("double", "requestedAmount")]),
            ("MO_OUT_LimitEvaluation", [("String", "partyId"), ("boolean", "approved"), ("double", "availableHeadroom"), ("String", "reasonCode")]),
            ("MO_INP_AmlScreening", [("String", "transactionId"), ("String", "senderId"), ("String", "receiverId"), ("double", "amount"), ("String", "countryCode")]),
            ("MO_OUT_AmlScreening", [("String", "screeningRef"), ("boolean", "flagged"), ("double", "riskScore"), ("String", "matchedRule")]),
            ("MO_INP_ExposureRecalculate", [("String", "counterpartyId"), ("double", "incrementalTradeAmount")]),
            ("MO_OUT_ExposureRecalculate", [("String", "exposureId"), ("double", "totalNetExposure"), ("boolean", "breach")]),
            ("MO_INP_RiskOverride", [("String", "limitId"), ("double", "overrideAmount"), ("String", "approvedBy")]),
            ("MO_OUT_RiskOverride", [("String", "overrideRef"), ("String", "status"), ("long", "effectiveUntil")]),
            ("MO_RiskMetricSummary", [("String", "portfolioId"), ("double", "totalVaR"), ("double", "expectedShortfall"), ("double", "stressLoss")]),
            ("MO_ComplianceViolation", [("String", "alertId"), ("String", "severity"), ("String", "entityId"), ("String", "description")]),
            ("MO_StressTestScenario", [("String", "scenarioId"), ("String", "name"), ("double", "shockPct"), ("double", "marketImpact")]),
            ("MO_CounterpartyRating", [("String", "partyId"), ("String", "creditRating"), ("double", "defaultProbability")])
        ],
        "data_grabbers": ["RKDGRiskGrabber", "RKDGLimitGrabber", "RKDGAmlAlertGrabber", "RKDGExposureGrabber"],
        "business_transactions": ["RKBTEvaluateLimit", "RKBTProcessAmlAlert", "RKBTRecalculateExposure", "RKBTApproveRiskOverride", "RKBTExecuteStressTest"],
        "elementary_transactions": ["RKETCalculateVaR", "RKETCheckCounterpartyLimit", "RKETQueryAmlStatus", "RKETFetchExposureBreakdown"],
        "batch_processors": ["RKPSEODRiskComputation", "RKPBPreRiskDataCollection", "RKPAPostRiskReporting"],
        "services": ["MarketRiskService", "CreditRiskService", "AmlScreeningService", "LimitManagementService", "StressTestingService", "RiskAnalyticsEngine"],
        "controllers": ["RiskAssessmentController", "ComplianceAuditController", "ExposureMonitorController"],
        "enums": [
            ("RiskLevel", ["LOW", "MEDIUM", "HIGH", "CRITICAL", "UNACCEPTABLE"]),
            ("LimitCategory", ["INTRADAY_SETTLEMENT", "CREDIT_LINE", "TENOR_LIMIT", "COUNTRY_LIMIT", "NOTIONAL_CAP"]),
            ("AmlStatus", ["CLEARED", "UNDER_REVIEW", "ESCALATED", "SAR_FILED", "BLOCKED"]),
            ("ExposureType", ["MARKET_RISK", "CREDIT_DEFAULT", "SETTLEMENT_RISK", "OPERATIONAL_RISK"]),
            ("StressScenario", ["HISTORICAL_2008", "COVID_SHOCK", "RATE_HIKE_300BPS", "LIQUIDITY_FREEZE"])
        ],
        "records": [
            ("VaRMetricRecord", [("String", "portfolioId"), ("double", "var99"), ("double", "var95")]),
            ("AmlScoreRecord", [("String", "txnId"), ("double", "score"), ("String", "verdict")]),
            ("LimitBreachRecord", [("String", "limitId"), ("double", "breachAmount"), ("long", "time")]),
            ("StressImpactRecord", [("String", "scenarioId"), ("double", "lossEstimate")])
        ],
        "interfaces": [
            ("RiskEvaluator", ["boolean checkPreTradeLimit(String partyId, double amount)", "double computeCapitalCharge(String exposureId)"]),
            ("ComplianceScreeningEngine", ["MO_OUT_AmlScreening screenTransaction(MO_INP_AmlScreening req)"]),
            ("LimitRegistry", ["void registerLimit(String partyId, double amount)", "void releaseLimit(String partyId, double amount)"])
        ]
    },

    "MS": {
        "name": "Financial Messaging & Protocols",
        "cross_deps": ["AM", "PM", "common"],
        "persistent_classes": [
            {
                "name": "MessageHeaderRecord",
                "id_field": "messageId",
                "fields": [
                    ("String", "messageId"), ("String", "protocolType"), ("String", "messageType"),
                    ("String", "senderBic"), ("String", "receiverBic"), ("int", "sessionNumber"),
                    ("int", "sequenceNumber"), ("String", "priority"), ("String", "checksum"),
                    ("String", "dispatchStatus")
                ],
                "business_methods": [
                    ("updateStatus", [("String", "status")], "dispatchStatus = status;"),
                    ("verifyChecksum", [("String", "receivedChecksum")], "if (checksum != null && checksum.equals(receivedChecksum)) dispatchStatus = \"VERIFIED\";"),
                    ("assignSequence", [("int", "seq")], "sequenceNumber = seq;")
                ]
            },
            {
                "name": "InboundPayloadStore",
                "id_field": "payloadId",
                "fields": [
                    ("String", "payloadId"), ("String", "messageId"), ("String", "rawPayload"),
                    ("String", "parsedXmlJson"), ("long", "receivedTimestamp"), ("String", "processingStatus"),
                    ("int", "retryCount")
                ],
                "business_methods": [
                    ("markProcessed", [], "processingStatus = \"PROCESSED\";"),
                    ("incrementRetry", [], "retryCount = retryCount + 1; if (retryCount > 3) processingStatus = \"DEAD_LETTER\";")
                ]
            },
            {
                "name": "OutboundDispatchQueue",
                "id_field": "dispatchId",
                "fields": [
                    ("String", "dispatchId"), ("String", "destinationQueue"), ("String", "formattedMessage"),
                    ("long", "scheduledTime"), ("String", "deliveryReceipt"), ("String", "status")
                ],
                "business_methods": [
                    ("markDelivered", [("String", "receipt")], "deliveryReceipt = receipt; status = \"DELIVERED\";"),
                    ("failDispatch", [("String", "error")], "deliveryReceipt = error; status = \"FAILED\";")
                ]
            },
            {
                "name": "TransformationRule",
                "id_field": "ruleId",
                "fields": [
                    ("String", "ruleId"), ("String", "sourceFormat"), ("String", "targetFormat"),
                    ("String", "mappingDefinition"), ("int", "version"), ("boolean", "isActive")
                ],
                "business_methods": [
                    ("updateRule", [("String", "mapping")], "mappingDefinition = mapping; version = version + 1;")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_SwiftMT103", [("String", "senderBic"), ("String", "receiverBic"), ("String", "orderingCustomer"), ("String", "beneficiaryCustomer"), ("double", "amount"), ("String", "currency")]),
            ("MO_OUT_SwiftMT103", [("String", "murReference"), ("String", "status"), ("String", "ackNack"), ("long", "sendTime")]),
            ("MO_INP_IsoPacs008", [("String", "endToEndId"), ("String", "debtorIban"), ("String", "creditorIban"), ("double", "interbankAmount"), ("String", "settlementDate")]),
            ("MO_OUT_IsoPacs008", [("String", "txId"), ("String", "clearingStatus"), ("String", "reasonCode")]),
            ("MO_INP_FixNewOrderSingle", [("String", "clOrdId"), ("String", "symbol"), ("String", "side"), ("int", "orderQty"), ("double", "price")]),
            ("MO_OUT_FixExecutionReport", [("String", "execId"), ("String", "clOrdId"), ("String", "ordStatus"), ("int", "cumQty"), ("double", "avgPx")]),
            ("MO_INP_TransformMessage", [("String", "sourceProtocol"), ("String", "targetProtocol"), ("String", "payload")]),
            ("MO_OUT_TransformMessage", [("String", "transformedPayload"), ("boolean", "success"), ("String", "error")]),
            ("MO_MessageEnvelope", [("String", "id"), ("String", "protocol"), ("String", "format"), ("String", "content")]),
            ("MO_DispatchReceipt", [("String", "dispatchId"), ("String", "queue"), ("long", "ackTime")]),
            ("MO_DeadLetterNotice", [("String", "messageId"), ("String", "exceptionMsg"), ("int", "attempts")]),
            ("MO_ProtocolSessionState", [("String", "sessionId"), ("String", "protocol"), ("boolean", "connected"), ("int", "inSeq"), ("int", "outSeq")])
        ],
        "data_grabbers": ["MSDGMessageGrabber", "MSDGAuditQueueGrabber", "MSDGPayloadGrabber", "MSDGRoutingGrabber"],
        "business_transactions": ["MSBTRouteInboundMessage", "MSBTDispatchOutboundMessage", "MSBTTransformPayload", "MSBTAcknowledgeMessage", "MSBTProcessDeadLetter"],
        "elementary_transactions": ["MSETQueryMessageStatus", "MSETGetPayloadAudit", "MSETInspectQueueHealth", "MSETValidateSchema"],
        "batch_processors": ["MSPSEODQueueArchive", "MSPBPreArchiveValidation", "MSPAPostArchiveReconcile"],
        "services": ["SwiftParserService", "Iso20022TransformationService", "FixEngineIntegrationService", "MessageRoutingService", "DeadLetterQueueService", "ProtocolValidationService"],
        "controllers": ["MessagingBridgeController", "SwiftGatewayController", "FixChannelController"],
        "enums": [
            ("ProtocolFormat", ["SWIFT_FIN", "ISO_20022_XML", "FIX_5_0", "JSON_API", "BAI2"]),
            ("MessagePriority", ["NORMAL", "URGENT", "SYSTEM_OVERRIDE", "BATCH_BULK"]),
            ("ProcessingState", ["RECEIVED", "PARSED", "VALIDATED", "DISPATCHED", "FAILED"]),
            ("DispatchChannel", ["MQ_SERIES", "KAFKA_STREAM", "REST_WEBHOOK", "SWIFT_ALLIANCE"]),
            ("AckType", ["POSITIVE_ACK", "NEGATIVE_NACK", "TIMEOUT", "SCHEMA_REJECT"])
        ],
        "records": [
            ("SwiftBlockRecord", [("int", "blockNumber"), ("String", "content")]),
            ("IsoHeaderRecord", [("String", "bizMsgId"), ("String", "creationDate")]),
            ("FixFieldRecord", [("int", "tag"), ("String", "value")]),
            ("RoutingHopRecord", [("String", "hopId"), ("String", "nodeName"), ("long", "latencyMs")])
        ],
        "interfaces": [
            ("MessageDispatcher", ["boolean dispatch(String queue, String msg)", "String receive(String queue)"]),
            ("ProtocolTranslator", ["String translate(String input, String fromFormat, String toFormat)"]),
            ("ChannelHeartbeatListener", ["void onHeartbeat(String channel, boolean isAlive)"])
        ]
    },

    "AN": {
        "name": "Quantitative Analytics & Regulatory Reporting",
        "cross_deps": ["AM", "TR", "GL", "common"],
        "persistent_classes": [
            {
                "name": "PnLSummaryRecord",
                "id_field": "pnlId",
                "fields": [
                    ("String", "pnlId"), ("String", "businessDate"), ("String", "deskId"),
                    ("String", "portfolioId"), ("double", "realizedPnL"), ("double", "unrealizedPnL"),
                    ("double", "feeIncome"), ("double", "interestExpense"), ("double", "totalNetPnL")
                ],
                "business_methods": [
                    ("recalculateAttribution", [("double", "realized"), ("double", "unrealized")], "realizedPnL = realized; unrealizedPnL = unrealized; totalNetPnL = realizedPnL + unrealizedPnL + feeIncome - interestExpense;"),
                    ("publishMetrics", [("String", "date")], "businessDate = date;")
                ]
            },
            {
                "name": "YieldCurveSnapshot",
                "id_field": "curveId",
                "fields": [
                    ("String", "curveId"), ("String", "currency"), ("String", "referenceIndex"),
                    ("int", "tenorDays"), ("double", "zeroCouponRate"), ("double", "discountFactor"),
                    ("String", "asOfDate")
                ],
                "business_methods": [
                    ("interpolateRate", [("int", "days")], "tenorDays = days; discountFactor = Math.exp(-zeroCouponRate * (days / 365.0));"),
                    ("calibrateCurve", [("double", "rate")], "zeroCouponRate = rate;")
                ]
            },
            {
                "name": "LiquidityMetrics",
                "id_field": "metricId",
                "fields": [
                    ("String", "metricId"), ("String", "calculationDate"), ("double", "highQualityLiquidAssets"),
                    ("double", "totalNetCashOutflow30d"), ("double", "lcrRatio"), ("double", "availableStableFunding"),
                    ("double", "nsfrRatio"), ("String", "complianceStatus")
                ],
                "business_methods": [
                    ("computeLCR", [], "lcrRatio = (totalNetCashOutflow30d > 0) ? (highQualityLiquidAssets / totalNetCashOutflow30d) * 100.0 : 100.0; complianceStatus = (lcrRatio >= 100.0) ? \"COMPLIANT\" : \"BREACH\";"),
                    ("validateRatios", [], "if (nsfrRatio >= 100.0 && lcrRatio >= 100.0) complianceStatus = \"COMPLIANT\"; else complianceStatus = \"DEFICIT\";")
                ]
            },
            {
                "name": "RegulatoryReportSnapshot",
                "id_field": "reportId",
                "fields": [
                    ("String", "reportId"), ("String", "reportType"), ("String", "reportingPeriod"),
                    ("String", "submissionStatus"), ("double", "totalRiskWeightedAssets"), ("double", "capitalAdequacyRatio"),
                    ("long", "submissionTimestamp")
                ],
                "business_methods": [
                    ("approveSubmission", [("String", "user")], "submissionStatus = \"APPROVED\"; submissionTimestamp = System.currentTimeMillis();")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_PnLCalculation", [("String", "portfolioId"), ("String", "date"), ("String", "methodology")]),
            ("MO_OUT_PnLCalculation", [("String", "pnlId"), ("double", "totalPnL"), ("double", "realized"), ("double", "unrealized")]),
            ("MO_INP_YieldCurveQuery", [("String", "currency"), ("String", "index"), ("int", "tenorDays")]),
            ("MO_OUT_YieldCurveQuery", [("String", "curveId"), ("double", "rate"), ("double", "discountFactor")]),
            ("MO_INP_BaselReportGenerate", [("String", "reportType"), ("String", "period")]),
            ("MO_OUT_BaselReportGenerate", [("String", "reportId"), ("double", "carRatio"), ("double", "rwa"), ("String", "status")]),
            ("MO_INP_LiquidityStressCheck", [("double", "outflowShockPct"), ("double", "inflowHaircutPct")]),
            ("MO_OUT_LiquidityStressCheck", [("double", "projectedLcr"), ("boolean", "isCompliant")]),
            ("MO_PnLDecomposition", [("String", "deskId"), ("double", "fxPnL"), ("double", "interestPnL"), ("double", "equityPnL")]),
            ("MO_RegulatoryFiling", [("String", "reportId"), ("String", "regulatorName"), ("String", "filingRef"), ("String", "status")]),
            ("MO_AttributionFactor", [("String", "factorName"), ("double", "weight"), ("double", "contribution")]),
            ("MO_BenchmarkComparison", [("String", "portfolioId"), ("String", "benchmarkId"), ("double", "alpha"), ("double", "beta")])
        ],
        "data_grabbers": ["ANDGAnalyticsGrabber", "ANDGYieldCurveGrabber", "ANDGLiquidityGrabber", "ANDGReportGrabber"],
        "business_transactions": ["ANBTCalculatePnL", "ANBTCalibrateYieldCurve", "ANBTGenerateRegulatoryFiling", "ANBTExecuteLiquidityTest", "ANBTPublishMetrics"],
        "elementary_transactions": ["ANETGetPnLSummary", "ANETFetchYieldCurve", "ANETQueryCapitalAdequacy", "ANETGetRegulatoryFilingStatus"],
        "batch_processors": ["ANPSEODAnalyticsRollup", "ANPBPreRollupDataSanity", "ANPAPostRollupNotification"],
        "services": ["PnLCalculationService", "YieldCurveBootstrappingService", "BaselComplianceService", "LiquidityRiskModelingService", "ExecutiveDashboardService", "PerformanceAttributionService"],
        "controllers": ["AnalyticsReportingController", "PnLDashboardController", "RegulatoryComplianceController"],
        "enums": [
            ("ReportFrequency", ["DAILY", "MONTHLY", "QUARTERLY", "ANNUAL", "AD_HOC"]),
            ("CalculationMethodology", ["HISTORICAL_SIMULATION", "MONTE_CARLO", "PARAMETRIC_VARIANCE", "SENSITIVITY_DELTA"]),
            ("YieldCurveTenor", ["OVERNIGHT", "ONE_MONTH", "THREE_MONTH", "ONE_YEAR", "FIVE_YEAR", "TEN_YEAR"]),
            ("CapitalTier", ["COMMON_EQUITY_TIER_1", "ADDITIONAL_TIER_1", "TIER_2_CAPITAL"]),
            ("AttributionCategory", ["ASSET_ALLOCATION", "SECURITY_SELECTION", "CURRENCY_EFFECT", "INTEREST_RATE_SHIFT"])
        ],
        "records": [
            ("PnLBucketRecord", [("String", "category"), ("double", "amount")]),
            ("CurvePointRecord", [("int", "days"), ("double", "zeroRate")]),
            ("CapitalRatioRecord", [("String", "tier"), ("double", "ratioPct")]),
            ("AuditFilingRecord", [("String", "filingId"), ("String", "status"), ("long", "time")])
        ],
        "interfaces": [
            ("QuantitativeModel", ["double evaluate(Map<String, Double> inputs)", "String getModelVersion()"]),
            ("RegulatoryExporter", ["String exportToXml(String reportId)", "boolean submitToRegulator(String reportId)"]),
            ("YieldInterpolationEngine", ["double interpolate(int days, String currency)"])
        ]
    },

    "DP": {
        "name": "Term Deposits & Structured Products",
        "cross_deps": ["AM", "GL", "CU", "common"],
        "persistent_classes": [
            {
                "name": "DepositContract",
                "id_field": "depositId",
                "fields": [
                    ("String", "depositId"), ("String", "customerId"), ("String", "accountNumber"),
                    ("String", "depositProductCode"), ("double", "principalAmount"), ("int", "tenureDays"),
                    ("double", "interestRate"), ("String", "compoundingFrequency"), ("double", "maturityAmount"),
                    ("String", "maturityDate"), ("String", "renewalOption"), ("String", "depositStatus")
                ],
                "business_methods": [
                    ("accrueInterest", [("double", "dailyAccrual")], "maturityAmount = maturityAmount + dailyAccrual;"),
                    ("mature", [], "depositStatus = \"MATURED\";"),
                    ("liquidatePrematurely", [("double", "penalty")], "maturityAmount = principalAmount - penalty; depositStatus = \"PREMATURELY_CLOSED\";"),
                    ("renew", [("int", "additionalDays"), ("double", "newRate")], "tenureDays = tenureDays + additionalDays; interestRate = newRate; depositStatus = \"RENEWED\";")
                ]
            },
            {
                "name": "DepositInterestLedger",
                "id_field": "ledgerId",
                "fields": [
                    ("String", "ledgerId"), ("String", "depositId"), ("String", "calculationPeriodStart"),
                    ("String", "calculationPeriodEnd"), ("double", "accruedAmount"), ("double", "taxDeductedAtSource"),
                    ("boolean", "isPosted")
                ],
                "business_methods": [
                    ("postAccrual", [], "isPosted = true;"),
                    ("deductTds", [("double", "tdsAmount")], "taxDeductedAtSource = taxDeductedAtSource + tdsAmount; accruedAmount = accruedAmount - tdsAmount;")
                ]
            },
            {
                "name": "PrematurePenaltyRule",
                "id_field": "ruleId",
                "fields": [
                    ("String", "ruleId"), ("String", "depositProductCode"), ("int", "minimumTenureMonths"),
                    ("double", "penaltyRateDeduction"), ("String", "effectiveDate")
                ],
                "business_methods": [
                    ("computePenalty", [("double", "principal"), ("double", "rate")], "penaltyRateDeduction = Math.min(2.0, penaltyRateDeduction);")
                ]
            },
            {
                "name": "RecurringDepositSchedule",
                "id_field": "scheduleId",
                "fields": [
                    ("String", "scheduleId"), ("String", "depositId"), ("int", "installmentNumber"),
                    ("double", "monthlyInstallment"), ("String", "dueDate"), ("String", "installmentStatus")
                ],
                "business_methods": [
                    ("recordPayment", [("String", "date")], "installmentStatus = \"PAID\"; dueDate = date;")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_DepositBooking", [("String", "customerId"), ("String", "fundingAccount"), ("double", "principal"), ("int", "tenureDays"), ("String", "productCode")]),
            ("MO_OUT_DepositBooking", [("String", "depositId"), ("String", "status"), ("double", "maturityAmount"), ("String", "maturityDate"), ("double", "interestRate")]),
            ("MO_INP_PrematureWithdrawal", [("String", "depositId"), ("String", "reason"), ("String", "payoutAccount")]),
            ("MO_OUT_PrematureWithdrawal", [("String", "depositId"), ("double", "refundAmount"), ("double", "penaltyDeducted"), ("String", "status")]),
            ("MO_INP_MaturityInstruction", [("String", "depositId"), ("String", "action"), ("String", "beneficiaryAccount")]),
            ("MO_OUT_MaturityInstruction", [("String", "depositId"), ("String", "updatedAction"), ("boolean", "confirmed")]),
            ("MO_DepositCertificate", [("String", "certificateNumber"), ("String", "depositId"), ("String", "holderName"), ("double", "principal"), ("double", "maturityValue")]),
            ("MO_InterestAccrualSchedule", [("String", "depositId"), ("int", "periods"), ("double", "totalExpectedInterest")]),
            ("MO_INP_RateQuote", [("double", "amount"), ("int", "tenureDays"), ("String", "customerCategory")]),
            ("MO_OUT_RateQuote", [("double", "applicableRate"), ("double", "seniorCitizenBonus"), ("String", "validUntil")]),
            ("MO_TdsCertificate", [("String", "depositId"), ("double", "grossInterest"), ("double", "taxDeducted"), ("String", "financialYear")]),
            ("MO_DepositRolloverReport", [("String", "depositId"), ("String", "newMaturityDate"), ("double", "newPrincipal")])
        ],
        "data_grabbers": ["DPDGDepositGrabber", "DPDGInterestLedgerGrabber", "DPDGPenaltyRuleGrabber", "DPDGMaturityGrabber"],
        "business_transactions": ["DPBTBookDeposit", "DPBTLiquidatePrematurely", "DPBTMatureDeposit", "DPBTRenewContract", "DPBTAccrueDepositInterest"],
        "elementary_transactions": ["DPETGetDepositDetails", "DPETSimulateMaturityValue", "DPETCalculateBreakValue", "DPETQueryActiveDeposits"],
        "batch_processors": ["DPPSEODMaturityBatch", "DPPBPreMaturityValidation", "DPPAPostMaturityDisbursement"],
        "services": ["DepositBookingService", "DepositInterestEngine", "PrematureLiquidationService", "MaturityProcessingService", "TaxDeductionService", "DepositCertificateService"],
        "controllers": ["DepositProductController", "DepositServicingController", "DepositMaturityController"],
        "enums": [
            ("DepositProductType", ["FIXED_DEPOSIT", "RECURRING_DEPOSIT", "CERTIFICATE_OF_DEPOSIT", "TAX_SAVER_DEPOSIT", "FLEXI_DEPOSIT"]),
            ("CompoundingFrequency", ["MONTHLY", "QUARTERLY", "HALF_YEARLY", "ANNUALLY", "AT_MATURITY"]),
            ("RenewalAction", ["AUTO_RENEW_PRINCIPAL_AND_INTEREST", "AUTO_RENEW_PRINCIPAL_ONLY", "CREDIT_TO_ACCOUNT", "ISSUE_CASHIERS_CHECK"]),
            ("DepositLifecycleStatus", ["OPEN", "ACTIVE", "MATURED", "PREMATURELY_CLOSED", "TRANSFERRED"]),
            ("InterestPayoutMode", ["MONTHLY_PAYOUT", "QUARTERLY_PAYOUT", "CUMULATIVE_GROWTH"])
        ],
        "records": [
            ("DepositMaturityRecord", [("String", "depositId"), ("double", "finalAmount"), ("String", "date")]),
            ("TdsDeductionRecord", [("String", "depositId"), ("double", "taxAmount"), ("long", "time")]),
            ("RenewalLogRecord", [("String", "depositId"), ("int", "extraDays"), ("double", "newRate")]),
            ("InterestAccrualSnapshot", [("String", "depositId"), ("double", "accrued"), ("String", "period")])
        ],
        "interfaces": [
            ("DepositCalculator", ["double computeMaturityValue(double principal, double rate, int days, String compounding)", "double calculateBreakPenalty(double principal, double rate, int elapsedDays)"]),
            ("MaturityNotificationListener", ["void onMaturityDue(String depositId, String customerId, double maturityAmount)"]),
            ("TaxWithholdingCalculator", ["double computeTds(double interestEarned, String panNumber, boolean isSeniorCitizen)"])
        ]
    },

    "GL": {
        "name": "General Ledger & Double-Entry Accounting",
        "cross_deps": ["AM", "LN", "TR", "CL", "PM", "common"],
        "persistent_classes": [
            {
                "name": "LedgerAccount",
                "id_field": "glCode",
                "fields": [
                    ("String", "glCode"), ("String", "glName"), ("String", "glCategory"),
                    ("String", "currency"), ("double", "currentDebitBalance"), ("double", "currentCreditBalance"),
                    ("double", "netBalance"), ("String", "reconciliationStatus"), ("boolean", "isBlocked")
                ],
                "business_methods": [
                    ("postDebit", [("double", "amount")], "currentDebitBalance = currentDebitBalance + amount; netBalance = currentDebitBalance - currentCreditBalance;"),
                    ("postCredit", [("double", "amount")], "currentCreditBalance = currentCreditBalance + amount; netBalance = currentDebitBalance - currentCreditBalance;"),
                    ("reconcile", [], "reconciliationStatus = \"RECONCILED\";"),
                    ("closePeriod", [], "if (netBalance != 0.0) reconciliationStatus = \"CARRIED_FORWARD\";")
                ]
            },
            {
                "name": "JournalVoucher",
                "id_field": "voucherId",
                "fields": [
                    ("String", "voucherId"), ("String", "transactionRef"), ("String", "postingDate"),
                    ("String", "valueDate"), ("String", "voucherType"), ("double", "totalDebitAmount"),
                    ("double", "totalCreditAmount"), ("String", "approvalStatus"), ("String", "postedBy")
                ],
                "business_methods": [
                    ("validateBalance", [], "if (Math.abs(totalDebitAmount - totalCreditAmount) < 0.001) approvalStatus = \"BALANCED\"; else approvalStatus = \"UNBALANCED\";"),
                    ("approveVoucher", [("String", "officer")], "approvalStatus = \"APPROVED\"; postedBy = officer;"),
                    ("postVoucher", [], "approvalStatus = \"POSTED\";")
                ]
            },
            {
                "name": "JournalPostingLeg",
                "id_field": "legId",
                "fields": [
                    ("String", "legId"), ("String", "voucherId"), ("String", "glCode"),
                    ("String", "legSide"), ("double", "amount"), ("String", "narration"), ("String", "costCenter")
                ],
                "business_methods": [
                    ("adjustAmount", [("double", "newAmt")], "amount = newAmt;")
                ]
            },
            {
                "name": "FinancialPeriod",
                "id_field": "periodId",
                "fields": [
                    ("String", "periodId"), ("int", "fiscalYear"), ("int", "periodNumber"),
                    ("String", "startDate"), ("String", "endDate"), ("String", "periodStatus")
                ],
                "business_methods": [
                    ("lockPeriod", [], "periodStatus = \"LOCKED\";"),
                    ("openPeriod", [], "periodStatus = \"OPEN\";")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_JournalEntry", [("String", "ref"), ("String", "date"), ("String", "voucherType"), ("String", "drGlCode"), ("String", "crGlCode"), ("double", "amount"), ("String", "narration")]),
            ("MO_OUT_JournalEntry", [("String", "voucherId"), ("String", "status"), ("double", "balancedAmount"), ("long", "timestamp")]),
            ("MO_INP_TrialBalanceQuery", [("int", "fiscalYear"), ("int", "periodNumber"), ("String", "currency")]),
            ("MO_OUT_TrialBalanceQuery", [("int", "accountCount"), ("double", "totalDebit"), ("double", "totalCredit"), ("boolean", "isBalanced")]),
            ("MO_INP_PeriodClose", [("String", "periodId"), ("String", "closedBy")]),
            ("MO_OUT_PeriodClose", [("String", "periodId"), ("String", "status"), ("int", "unpostedCount")]),
            ("MO_JournalLegItem", [("String", "glCode"), ("String", "side"), ("double", "amount"), ("String", "desc")]),
            ("MO_TrialBalanceItem", [("String", "glCode"), ("String", "glName"), ("double", "debit"), ("double", "credit")]),
            ("MO_LedgerAuditReport", [("String", "glCode"), ("double", "openingBalance"), ("double", "closingBalance"), ("int", "txnCount")]),
            ("MO_CostCenterRollup", [("String", "costCenter"), ("double", "totalExpense"), ("double", "totalRevenue")]),
            ("MO_IntercompanyClearingEntry", [("String", "fromEntity"), ("String", "toEntity"), ("double", "amount")]),
            ("MO_ReconciliationException", [("String", "glCode"), ("double", "variance"), ("String", "reason")])
        ],
        "data_grabbers": ["GLDGLedgerGrabber", "GLDGVoucherGrabber", "GLDGTrialBalanceGrabber", "GLDGPeriodGrabber"],
        "business_transactions": ["GLBTPostJournalEntry", "GLBTApproveVoucher", "GLBTCloseFiscalPeriod", "GLBTReconcileAccounts", "GLBTPostCorrectionLeg"],
        "elementary_transactions": ["GLETGetAccountBalance", "GLETFetchTrialBalance", "GLETQueryVoucher", "GLETValidateDoubleEntry"],
        "batch_processors": ["GLPSEODLedgerBalancing", "GLPBPreBalancingAudit", "GLPAPostBalancingReport"],
        "services": ["GeneralLedgerService", "JournalPostingService", "TrialBalanceCalculationService", "PeriodClosureService", "CostCenterAllocationService", "DoubleEntryVerificationService"],
        "controllers": ["GeneralLedgerController", "AccountingVoucherController", "FiscalPeriodController"],
        "enums": [
            ("GlCategory", ["ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE", "CONTRA_ASSET"]),
            ("PostingSide", ["DEBIT", "CREDIT"]),
            ("VoucherType", ["GENERAL_JOURNAL", "CASH_PAYMENT", "CASH_RECEIPT", "ADJUSTMENT_MEMO", "EOD_ACCRUAL"]),
            ("PeriodStatus", ["FUTURE", "OPEN", "SOFT_LOCKED", "LOCKED", "CLOSED"]),
            ("CostCenter", ["RETAIL_BANKING", "WEALTH_MANAGEMENT", "TREASURY_OPS", "INFORMATION_TECH", "COMPLIANCE"])
        ],
        "records": [
            ("VoucherBalanceRecord", [("String", "voucherId"), ("double", "dr"), ("double", "cr"), ("boolean", "balanced")]),
            ("PeriodCloseAuditRecord", [("String", "periodId"), ("String", "user"), ("long", "time")]),
            ("LedgerMovementRecord", [("String", "glCode"), ("double", "movement"), ("String", "side")]),
            ("DoubleEntryValidationRecord", [("String", "voucherId"), ("boolean", "valid"), ("String", "msg")])
        ],
        "interfaces": [
            ("LedgerPoster", ["String postDoubleEntry(String drGl, String crGl, double amount, String narration)", "boolean reverseVoucher(String voucherId)"]),
            ("AccountingRuleEngine", ["boolean validateGlCompatibility(String drGl, String crGl)", "String resolveDefaultGl(String eventType)"]),
            ("TrialBalanceObserver", ["void onUnbalancedConditionDetected(double drTotal, double crTotal)"])
        ]
    },

    "PM": {
        "name": "Payment Systems & Real-Time Gross Settlement",
        "cross_deps": ["AM", "GL", "RK", "common"],
        "persistent_classes": [
            {
                "name": "PaymentTransaction",
                "id_field": "paymentId",
                "fields": [
                    ("String", "paymentId"), ("String", "channelId"), ("String", "debtorIban"),
                    ("String", "creditorIban"), ("double", "amount"), ("String", "currency"),
                    ("String", "paymentMethod"), ("String", "clearingNetwork"), ("String", "settlementStatus"),
                    ("String", "endToEndId"), ("double", "feeAmount"), ("long", "creationTimestamp")
                ],
                "business_methods": [
                    ("routePayment", [("String", "network")], "clearingNetwork = network; settlementStatus = \"ROUTED\";"),
                    ("authorize", [], "settlementStatus = \"AUTHORIZED\";"),
                    ("settle", [], "settlementStatus = \"SETTLED\";"),
                    ("failPayment", [("String", "reason")], "settlementStatus = \"FAILED\"; clearingNetwork = reason;"),
                    ("reverse", [("double", "refundAmount")], "amount = amount - refundAmount; settlementStatus = \"REVERSED\";")
                ]
            },
            {
                "name": "RoutingDirectory",
                "id_field": "directoryId",
                "fields": [
                    ("String", "directoryId"), ("String", "bankCode"), ("String", "bic"),
                    ("String", "supportedNetwork"), ("String", "cutoffTimeUtc"), ("boolean", "isDirectParticipant")
                ],
                "business_methods": [
                    ("checkAvailability", [("String", "time")], "if (cutoffTimeUtc != null && cutoffTimeUtc.compareTo(time) > 0) isDirectParticipant = true;")
                ]
            },
            {
                "name": "PaymentMandate",
                "id_field": "mandateId",
                "fields": [
                    ("String", "mandateId"), ("String", "debtorAccount"), ("String", "creditorId"),
                    ("double", "maxDebitAmount"), ("String", "frequency"), ("String", "expiryDate"),
                    ("String", "mandateStatus")
                ],
                "business_methods": [
                    ("suspendMandate", [("String", "reason")], "mandateStatus = \"SUSPENDED\"; expiryDate = reason;"),
                    ("executeMandate", [("double", "amount")], "if (amount <= maxDebitAmount) mandateStatus = \"ACTIVE\"; else mandateStatus = \"EXCEEDED\";")
                ]
            },
            {
                "name": "ClearingReturnRecord",
                "id_field": "returnId",
                "fields": [
                    ("String", "returnId"), ("String", "originalPaymentId"), ("String", "returnReasonCode"),
                    ("double", "returnedAmount"), ("long", "returnTimestamp")
                ],
                "business_methods": [
                    ("acknowledgeReturn", [], "returnTimestamp = System.currentTimeMillis();")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_PaymentInitiation", [("String", "debtorAccount"), ("String", "creditorIban"), ("double", "amount"), ("String", "currency"), ("String", "method"), ("String", "narrative")]),
            ("MO_OUT_PaymentInitiation", [("String", "paymentId"), ("String", "endToEndId"), ("String", "status"), ("double", "fee"), ("long", "timestamp")]),
            ("MO_INP_PaymentStatusQuery", [("String", "paymentId"), ("String", "endToEndId")]),
            ("MO_OUT_PaymentStatusQuery", [("String", "paymentId"), ("String", "status"), ("String", "network"), ("double", "settledAmount")]),
            ("MO_INP_PaymentCancellation", [("String", "paymentId"), ("String", "cancellationReason")]),
            ("MO_OUT_PaymentCancellation", [("String", "paymentId"), ("String", "cancelStatus"), ("boolean", "reversalInitiated")]),
            ("MO_PaymentRoutingPath", [("String", "sourceBic"), ("String", "destBic"), ("String", "optimalNetwork"), ("double", "estimatedFee")]),
            ("MO_MandateDetails", [("String", "mandateId"), ("String", "debtor"), ("String", "creditor"), ("double", "limit"), ("String", "status")]),
            ("MO_INP_DirectDebitBatch", [("String", "creditorId"), ("int", "mandateCount"), ("double", "totalDebitSum")]),
            ("MO_OUT_DirectDebitBatch", [("String", "batchId"), ("int", "acceptedCount"), ("int", "rejectedCount")]),
            ("MO_LiquidityReservation", [("String", "network"), ("double", "reservedAmount"), ("String", "status")]),
            ("MO_PaymentChannelStatus", [("String", "channel"), ("boolean", "isOperational"), ("double", "throughputTps")])
        ],
        "data_grabbers": ["PMDGPaymentGrabber", "PMDGRoutingGrabber", "PMDGMandateGrabber", "PMDGClearingQueueGrabber"],
        "business_transactions": ["PMBTInitiatePayment", "PMBTAuthorizePayment", "PMBTSettlePaymentInstruction", "PMBTCancelPayment", "PMBTExecuteDirectDebit"],
        "elementary_transactions": ["PMETGetPaymentStatus", "PMETCheckRoutingPath", "PMETValidateIban", "PMETQueryMandate"],
        "batch_processors": ["PMPSEODPaymentClearing", "PMPBPreClearingValidation", "PMPAPostClearingReconcile"],
        "services": ["PaymentInitiationService", "PaymentRoutingEngine", "PaymentValidationService", "DirectDebitMandateService", "FeeDeductionService", "ClearingNetworkAdapterService"],
        "controllers": ["PaymentGatewayController", "PaymentMandateController", "PaymentRoutingController"],
        "enums": [
            ("PaymentMethod", ["SEPA_CREDIT_TRANSFER", "SEPA_INSTANT", "FEDWIRE", "CHIPS", "RTGS_DOMESTIC", "ACH_DIRECT_DEBIT"]),
            ("ClearingNetwork", ["EBA_STEP2", "TARGET2", "FEDNOW", "SWIFT_GPI", "LOCAL_ACH"]),
            ("PaymentStatus", ["INITIATED", "ROUTED", "AUTHORIZED", "PENDING_SETTLEMENT", "SETTLED", "REJECTED", "REVERSED"]),
            ("MandateFrequency", ["ONE_OFF", "WEEKLY", "MONTHLY", "QUARTERLY", "ANNUALLY"]),
            ("ChargeBearer", ["DEBT", "CRED", "SHAR", "SLEV"])
        ],
        "records": [
            ("PaymentAuditRecord", [("String", "paymentId"), ("String", "event"), ("long", "time")]),
            ("RoutingHopRecord", [("String", "network"), ("String", "bic"), ("double", "cost")]),
            ("MandateExecutionRecord", [("String", "mandateId"), ("double", "amt"), ("boolean", "success")]),
            ("ClearingSettlementAckRecord", [("String", "paymentId"), ("String", "clearingRef")])
        ],
        "interfaces": [
            ("PaymentRouter", ["String selectOptimalRoute(String debtorBic, String creditorBic, double amount, String currency)"]),
            ("PaymentValidator", ["boolean validatePaymentRequest(MO_INP_PaymentInitiation req)"]),
            ("ClearingNetworkGateway", ["boolean dispatchToNetwork(String network, String paymentPayload)"])
        ]
    },

    "CU": {
        "name": "Customer Information File & KYC / AML",
        "cross_deps": ["common"],
        "persistent_classes": [
            {
                "name": "CustomerProfile",
                "id_field": "customerId",
                "fields": [
                    ("String", "customerId"), ("String", "taxId"), ("String", "customerType"),
                    ("String", "fullName"), ("String", "riskRating"), ("String", "kycStatus"),
                    ("String", "segment"), ("String", "incorporationCountry"), ("double", "totalExposureAmount"),
                    ("long", "onboardingDate"), ("boolean", "isActive")
                ],
                "business_methods": [
                    ("updateKycStatus", [("String", "newStatus")], "kycStatus = newStatus;"),
                    ("adjustRiskRating", [("String", "newRating")], "riskRating = newRating;"),
                    ("updateExposure", [("double", "delta")], "totalExposureAmount = totalExposureAmount + delta;"),
                    ("deactivateCustomer", [("String", "reason")], "isActive = false; segment = reason;")
                ]
            },
            {
                "name": "KycDocument",
                "id_field": "documentId",
                "fields": [
                    ("String", "documentId"), ("String", "customerId"), ("String", "documentType"),
                    ("String", "documentNumber"), ("String", "issuingAuthority"), ("String", "expiryDate"),
                    ("String", "verificationStatus"), ("String", "verifiedBy")
                ],
                "business_methods": [
                    ("verifyDocument", [("String", "officer")], "verificationStatus = \"VERIFIED\"; verifiedBy = officer;"),
                    ("rejectDocument", [("String", "reason")], "verificationStatus = \"REJECTED\"; issuingAuthority = reason;")
                ]
            },
            {
                "name": "PartyRelationship",
                "id_field": "relationshipId",
                "fields": [
                    ("String", "relationshipId"), ("String", "parentCustomerId"), ("String", "childCustomerId"),
                    ("String", "relationType"), ("double", "shareholdingPercentage"), ("boolean", "isAuthorizedSignatory")
                ],
                "business_methods": [
                    ("updateShareholding", [("double", "pct")], "shareholdingPercentage = pct; if (pct > 25.0) isAuthorizedSignatory = true;")
                ]
            },
            {
                "name": "CustomerPepScreening",
                "id_field": "screeningId",
                "fields": [
                    ("String", "screeningId"), ("String", "customerId"), ("boolean", "isPoliticallyExposed"),
                    ("String", "pepJurisdiction"), ("String", "sanctionListMatch"), ("String", "screeningResult")
                ],
                "business_methods": [
                    ("flagSanctionMatch", [("String", "listName")], "sanctionListMatch = listName; screeningResult = \"MATCH_FOUND\";")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_CustomerOnboarding", [("String", "taxId"), ("String", "name"), ("String", "customerType"), ("String", "country"), ("String", "segment")]),
            ("MO_OUT_CustomerOnboarding", [("String", "customerId"), ("String", "status"), ("String", "assignedRiskRating"), ("long", "timestamp")]),
            ("MO_INP_KycSubmission", [("String", "customerId"), ("String", "documentType"), ("String", "documentNumber"), ("String", "expiry")]),
            ("MO_OUT_KycSubmission", [("String", "documentId"), ("String", "status"), ("boolean", "passedOCR")]),
            ("MO_INP_RiskRatingUpdate", [("String", "customerId"), ("String", "proposedRating"), ("String", "rationale")]),
            ("MO_OUT_RiskRatingUpdate", [("String", "customerId"), ("String", "newRating"), ("boolean", "overrideApproved")]),
            ("MO_CustomerRelationshipMap", [("String", "customerId"), ("int", "subsidiaryCount"), ("double", "groupExposure")]),
            ("MO_KycDocumentSummary", [("String", "docId"), ("String", "type"), ("String", "status"), ("String", "expiry")]),
            ("MO_INP_BeneficialOwnerDeclaration", [("String", "corporateCustomerId"), ("String", "individualName"), ("double", "ownershipPct")]),
            ("MO_OUT_BeneficialOwnerDeclaration", [("String", "declarationId"), ("boolean", "verified")]),
            ("MO_CustomerCreditScore", [("String", "customerId"), ("int", "bureauScore"), ("String", "bureauAgency")]),
            ("MO_CustomerDormancyAlert", [("String", "customerId"), ("int", "daysInactive"), ("String", "recommendedAction")])
        ],
        "data_grabbers": ["CUDGCustomerGrabber", "CUDGKycGrabber", "CUDGRelationshipGrabber", "CUDGExposureRollupGrabber"],
        "business_transactions": ["CUBTOnboardCustomer", "CUBTVerifyKyc", "CUBTUpdateRiskProfile", "CUBTLinkPartyRelationship", "CUBTDeactivateCustomer"],
        "elementary_transactions": ["CUETGetCustomerProfile", "CUETQueryKycStatus", "CUETFetchRelationships", "CUETValidateTaxId"],
        "batch_processors": ["CUPSEODKycExpiryCheck", "CUPBPreKycAudit", "CUPAPostKycNotification"],
        "services": ["CustomerOnboardingService", "KycVerificationService", "CustomerRiskRatingService", "BeneficialOwnershipService", "CustomerExposureRollupService", "PepSanctionsCheckService"],
        "controllers": ["CustomerManagementController", "KycComplianceController", "CustomerRelationshipController"],
        "enums": [
            ("CustomerType", ["INDIVIDUAL", "CORPORATE", "FINANCIAL_INSTITUTION", "GOVERNMENT_ENTITY", "TRUST"]),
            ("KycStatus", ["NOT_STARTED", "IN_PROGRESS", "VERIFIED", "EXPIRED", "REJECTED"]),
            ("RiskRating", ["LOW_RISK", "STANDARD_RISK", "MEDIUM_RISK", "HIGH_RISK", "PROHIBITED"]),
            ("DocumentType", ["PASSPORT", "NATIONAL_ID", "DRIVERS_LICENSE", "CERTIFICATE_OF_INCORPORATION", "UTILITY_BILL"]),
            ("RelationType", ["PARENT_COMPANY", "SUBSIDIARY", "BENEFICIAL_OWNER", "DIRECTOR", "AUTHORIZED_SIGNER"])
        ],
        "records": [
            ("KycVerificationRecord", [("String", "docId"), ("String", "status"), ("String", "officer")]),
            ("RiskTransitionRecord", [("String", "customerId"), ("String", "oldRating"), ("String", "newRating")]),
            ("CustomerAuditRecord", [("String", "customerId"), ("String", "fieldChanged"), ("long", "time")]),
            ("PepHitRecord", [("String", "customerId"), ("String", "matchDetails"), ("boolean", "cleared")])
        ],
        "interfaces": [
            ("CustomerRegistry", ["CustomerProfile findCustomer(String customerId)", "boolean registerCustomer(CustomerProfile profile)"]),
            ("KycValidator", ["boolean validateKycCompliance(String customerId)", "String checkExpiry(String documentId)"]),
            ("ExposureAggregator", ["double rollupGroupExposure(String rootCustomerId)"])
        ]
    },

    "SC": {
        "name": "Security Pledges & Collateral Management",
        "cross_deps": ["LN", "RK", "common"],
        "persistent_classes": [
            {
                "name": "CollateralItem",
                "id_field": "collateralId",
                "fields": [
                    ("String", "collateralId"), ("String", "customerId"), ("String", "collateralType"),
                    ("String", "description"), ("double", "marketValue"), ("double", "haircutPct"),
                    ("double", "appraisedValue"), ("double", "assignedLtvRatio"), ("double", "encumbranceAmount"),
                    ("String", "lienStatus"), ("String", "valuationDate")
                ],
                "business_methods": [
                    ("revalue", [("double", "newMktValue"), ("String", "date")], "marketValue = newMktValue; valuationDate = date; appraisedValue = marketValue * (1.0 - haircutPct);"),
                    ("imposeLien", [("double", "encumberAmt")], "encumbranceAmount = encumbranceAmount + encumberAmt; lienStatus = \"ENCUMBERED\";"),
                    ("releaseLien", [("double", "releaseAmt")], "encumbranceAmount = Math.max(0.0, encumbranceAmount - releaseAmt); if (encumbranceAmount == 0.0) lienStatus = \"UNENCUMBERED\";"),
                    ("liquidate", [("double", "recoveryAmount")], "marketValue = 0.0; appraisedValue = 0.0; lienStatus = \"LIQUIDATED\";")
                ]
            },
            {
                "name": "CollateralPledge",
                "id_field": "pledgeId",
                "fields": [
                    ("String", "pledgeId"), ("String", "collateralId"), ("String", "facilityReferenceId"),
                    ("double", "pledgedAmount"), ("int", "pledgePriority"), ("String", "pledgeStatus")
                ],
                "business_methods": [
                    ("enforcePledge", [], "pledgeStatus = \"ENFORCED\";"),
                    ("releasePledge", [], "pledgeStatus = \"RELEASED\"; pledgedAmount = 0.0;")
                ]
            },
            {
                "name": "MarginCallEvent",
                "id_field": "callId",
                "fields": [
                    ("String", "callId"), ("String", "facilityId"), ("double", "requiredMargin"),
                    ("double", "currentCollateralValue"), ("double", "deficitAmount"), ("int", "curePeriodHours"),
                    ("String", "callStatus"), ("long", "issuedTimestamp")
                ],
                "business_methods": [
                    ("issueMarginCall", [("double", "deficit")], "deficitAmount = deficit; callStatus = \"ISSUED\"; issuedTimestamp = System.currentTimeMillis();"),
                    ("satisfyMarginCall", [], "callStatus = \"SATISFIED\"; deficitAmount = 0.0;"),
                    ("triggerDefault", [], "callStatus = \"DEFAULTED\";")
                ]
            },
            {
                "name": "ValuationAppraisalReport",
                "id_field": "appraisalId",
                "fields": [
                    ("String", "appraisalId"), ("String", "collateralId"), ("String", "appraiserAgency"),
                    ("double", "appraisedValue"), ("String", "appraisalDate"), ("String", "methodology")
                ],
                "business_methods": [
                    ("acceptAppraisal", [("double", "val")], "appraisedValue = val; methodology = \"ACCEPTED\";")
                ]
            }
        ],
        "message_objects": [
            ("MO_INP_CollateralRegistration", [("String", "customerId"), ("String", "type"), ("String", "description"), ("double", "estimatedValue"), ("double", "haircut")]),
            ("MO_OUT_CollateralRegistration", [("String", "collateralId"), ("String", "status"), ("double", "appraisedValue")]),
            ("MO_INP_CollateralRevaluation", [("String", "collateralId"), ("double", "newMarkVal"), ("String", "appraisalRef")]),
            ("MO_OUT_CollateralRevaluation", [("String", "collateralId"), ("double", "oldAppraised"), ("double", "newAppraised"), ("double", "changePct")]),
            ("MO_INP_PledgeCreation", [("String", "collateralId"), ("String", "facilityId"), ("double", "pledgeAmount"), ("int", "priority")]),
            ("MO_OUT_PledgeCreation", [("String", "pledgeId"), ("String", "status"), ("double", "remainingAvailableCollateral")]),
            ("MO_INP_MarginCallIssue", [("String", "facilityId"), ("double", "requiredMargin"), ("double", "deficit")]),
            ("MO_OUT_MarginCallIssue", [("String", "callId"), ("String", "status"), ("long", "deadline")]),
            ("MO_CollateralValuationReport", [("String", "collateralId"), ("double", "marketValue"), ("double", "haircut"), ("double", "netEligible")]),
            ("MO_LtvBreachAlert", [("String", "facilityId"), ("double", "currentLtv"), ("double", "covenantLtv"), ("double", "shortfall")]),
            ("MO_CollateralReleaseRequest", [("String", "pledgeId"), ("String", "reason")]),
            ("MO_LienStatusResponse", [("String", "collateralId"), ("String", "lienStatus"), ("double", "encumbered")])
        ],
        "data_grabbers": ["SCDGCollateralGrabber", "SCDGPledgeGrabber", "SCDGMarginCallGrabber", "SCDGValuationGrabber"],
        "business_transactions": ["SCBTRegisterCollateral", "SCBTRevalueCollateral", "SCBTCapitalizePledge", "SCBTIssueMarginCall", "SCBTReleaseLien"],
        "elementary_transactions": ["SCETGetCollateralDetails", "SCETCalculateLTV", "SCETQueryActivePledges", "SCETCheckMarginDeficit"],
        "batch_processors": ["SCPSEODLtvMonitoring", "SCPBPreLtvScan", "SCPAPostMarginAlerts"],
        "services": ["CollateralRegistrationService", "ValuationEngineService", "LtvMonitoringService", "MarginCallManagementService", "LienManagementService", "HaircutCalculationService"],
        "controllers": ["CollateralManagementController", "MarginCallController", "PledgeAdministrationController"],
        "enums": [
            ("CollateralType", ["REAL_ESTATE_COMMERCIAL", "REAL_ESTATE_RESIDENTIAL", "LISTED_EQUITY", "SOVEREIGN_BOND", "COMMODITY_GOLD", "CASH_DEPOSIT"]),
            ("LienStatus", ["UNENCUMBERED", "FIRST_CHARGE", "SECOND_CHARGE", "ENCUMBERED", "LIQUIDATED"]),
            ("MarginCallStatus", ["PENDING", "ISSUED", "SATISFIED", "EXTENDED", "DEFAULTED"]),
            ("PledgePriority", ["SENIOR_SECURED", "PARI_PASSU", "SUBORDINATED", "MEZZANINE"]),
            ("ValuationSource", ["INDEPENDENT_APPRAISAL", "AUTOMATED_VALUATION_MODEL", "MARKET_TICKER", "REGULATORY_SCHEDULE"])
        ],
        "records": [
            ("ValuationSnapshotRecord", [("String", "collateralId"), ("double", "val"), ("String", "date")]),
            ("MarginCallRecord", [("String", "callId"), ("String", "facilityId"), ("double", "deficit")]),
            ("PledgeMovementRecord", [("String", "pledgeId"), ("double", "amount"), ("long", "time")]),
            ("LtvCovenantRecord", [("String", "facilityId"), ("double", "ltvPct"), ("boolean", "inBreach")])
        ],
        "interfaces": [
            ("CollateralValuator", ["double computeNetValuation(CollateralItem item)", "double lookupHaircut(String collateralType)"]),
            ("LtvObserver", ["void onLtvBreached(String facilityId, double currentLtv, double maxLtv)"]),
            ("MarginCallEngine", ["MO_OUT_MarginCallIssue triggerMarginCall(String facilityId, double deficit)"])
        ]
    }
}


# templates.py
"""
Code generation templates for BaNCS archetypes:
  - Persistent Classes (Get, Create, Modify + business methods)
  - Message Objects (MO_INP_*, MO_OUT_*, MO_*)
  - Data Grabbers (*DG)
  - Business Transactions (*BT)
  - Elementary Transactions (*ET)
  - Batch Processors (*PS, *PB, *PA)
  - Services (*Service)
  - Controllers (*Controller)
  - Enums, Records, Interfaces
  - Common framework classes
"""

def generate_persistent_class(mod_code: str, entity: dict) -> str:
    name = entity["name"]
    id_field = entity["id_field"]
    fields = entity["fields"]
    biz_methods = entity.get("business_methods", [])

    field_decls = []
    getters_setters = []
    ctor_params = []
    ctor_assigns = []

    for ftype, fname in fields:
        field_decls.append(f"    private {ftype} {fname};")
        cap = fname[0].upper() + fname[1:]
        getters_setters.append(f"""    public {ftype} get{cap}() {{
        return this.{fname};
    }}
    public void set{cap}({ftype} {fname}) {{
        this.{fname} = {fname};
    }}""")
        ctor_params.append(f"{ftype} {fname}")
        ctor_assigns.append(f"        this.{fname} = {fname};")

    biz_code = []
    for mname, mparams, mbody in biz_methods:
        param_sig = ", ".join(f"{pt} {pn}" for pt, pn in mparams)
        biz_code.append(f"""    public synchronized void {mname}({param_sig}) {{
        {mbody}
        this.logStateChange("{mname}");
    }}""")

    return f"""package com.tcs.bancs.{mod_code};

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: {name}
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class {name} {{

{chr(10).join(field_decls)}
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public {name}() {{
    }}

    public {name}({", ".join(ctor_params)}) {{
{chr(10).join(ctor_assigns)}
        this.isPersisted = true;
    }}

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {{
        this.{id_field} = id;
        this.isPersisted = true;
        this.logStateChange("Get");
        return true;
    }}

    /**
     * Persists a newly created entity into underlying storage.
     */
    public synchronized boolean Create() {{
        this.isPersisted = true;
        this.entityVersion = "1.0";
        this.logStateChange("Create");
        return true;
    }}

    /**
     * Modifies persistent entity attributes and records mutation.
     */
    public synchronized boolean Modify(String newStatus) {{
        this.entityVersion = "1.1";
        this.logStateChange("Modify");
        return true;
    }}

    // ─────────────────────────────────────────────────────────────────────────
    // Business Methods (read, write, and propagate entity fields)
    // ─────────────────────────────────────────────────────────────────────────

{chr(10).join(biz_code)}

    private void logStateChange(String action) {{
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "{name}", String.valueOf(this.{id_field}), action);
    }}

    public boolean isPersisted() {{
        return this.isPersisted;
    }}

    public String getEntityVersion() {{
        return this.entityVersion;
    }}

{chr(10).join(getters_setters)}
}}
"""


def generate_message_object(mod_code: str, mo_name: str, fields: list) -> str:
    field_decls = []
    getters_setters = []
    ctor_params = []
    ctor_assigns = []

    for ftype, fname in fields:
        field_decls.append(f"    private {ftype} {fname};")
        cap = fname[0].upper() + fname[1:]
        getters_setters.append(f"""    public {ftype} get{cap}() {{
        return this.{fname};
    }}
    public void set{cap}({ftype} {fname}) {{
        this.{fname} = {fname};
    }}""")
        ctor_params.append(f"{ftype} {fname}")
        ctor_assigns.append(f"        this.{fname} = {fname};")

    return f"""package com.tcs.bancs.{mod_code};

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: {mo_name}
 * Standard DTO structure for request/response payloads and host integration.
 */
public class {mo_name} implements Serializable {{

    private static final long serialVersionUID = 1L;

{chr(10).join(field_decls)}
    private String messageCorrelationId;

    public {mo_name}() {{
        this.messageCorrelationId = UUID.randomUUID().toString();
    }}

    public {mo_name}({", ".join(ctor_params)}) {{
        this();
{chr(10).join(ctor_assigns)}
    }}

    public String getMessageCorrelationId() {{
        return this.messageCorrelationId;
    }}

    public void setMessageCorrelationId(String messageCorrelationId) {{
        this.messageCorrelationId = messageCorrelationId;
    }}

{chr(10).join(getters_setters)}

    @Override
    public String toString() {{
        return "{mo_name}{{" +
               "correlationId=\'" + messageCorrelationId + "\'" +
               "}}";
    }}
}}
"""


def generate_data_grabber(mod_code: str, dg_name: str, primary_entity: str, summary_mo: str) -> str:
    return f"""package com.tcs.bancs.{mod_code};

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Grabber: {dg_name}
 * Specialized query and data retrieval component for {mod_code} domain entities.
 */
public class {dg_name} {{

    private final Map<String, {primary_entity}> entityCache = new ConcurrentHashMap<>();

    public {dg_name}() {{
    }}

    public {primary_entity} fetch{primary_entity}ById(String id) {{
        if (id == null || id.isBlank()) {{
            return null;
        }}
        return entityCache.computeIfAbsent(id, k -> {{
            {primary_entity} entity = new {primary_entity}();
            entity.Get(k);
            return entity;
        }});
    }}

    public List<{primary_entity}> retrieveAll() {{
        List<{primary_entity}> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {{
            {primary_entity} sample = new {primary_entity}();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }}
        return results;
    }}

    public List<{primary_entity}> retrieveActive{primary_entity}s() {{
        return retrieveAll();
    }}

    public {summary_mo} grab{primary_entity}Summary(String id) {{
        {primary_entity} entity = fetch{primary_entity}ById(id);
        {summary_mo} summary = new {summary_mo}();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GRABBER_QUERY", "{dg_name}", id, "grabSummary");
        return summary;
    }}

    public boolean exists(String id) {{
        return id != null && (entityCache.containsKey(id) || id.length() > 3);
    }}

    public void invalidateCache(String id) {{
        if (id != null) {{
            entityCache.remove(id);
        }}
    }}
}}
"""


def generate_business_transaction(mod_code: str, bt_name: str, inp_mo: str, out_mo: str, primary_entity: str, dg_name: str, svc_name: str, cross_mod: str) -> str:
    action_method = f"{bt_name}Execute"

    cross_mod_call = ""
    cross_import = ""
    if cross_mod and cross_mod != "common":
        cross_import = f"import com.tcs.bancs.{cross_mod}.*;"
        cross_mod_call = f"""        // Cross-module integration: {mod_code} -> {cross_mod}
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "{bt_name}", req.getMessageCorrelationId(), "{cross_mod}");"""

    return f"""package com.tcs.bancs.{mod_code};

import java.util.*;
import com.tcs.bancs.common.*;
{cross_import}

/**
 * TCS BaNCS Business Transaction: {bt_name}
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class {bt_name} {{

    private final {dg_name} dataGrabber;
    private final {svc_name} service;

    public {bt_name}() {{
        this.dataGrabber = new {dg_name}();
        this.service = new {svc_name}();
    }}

    public {bt_name}({dg_name} dataGrabber, {svc_name} service) {{
        this.dataGrabber = dataGrabber;
        this.service = service;
    }}

    /**
     * Primary BaNCS Business Transaction entry point: {action_method}
     */
    public {out_mo} {action_method}({inp_mo} req) {{
        if (req == null) {{
            throw new ValidationException("Input message object cannot be null in " + "{bt_name}");
        }}

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {{
            throw new BusinessException("Validation failed in " + "{bt_name}");
        }}

        // Step 2: Data Grabber state query
        {primary_entity} entity = this.dataGrabber.fetch{primary_entity}ById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {{
            entity.Create();
            entity.Modify("EXECUTED");
        }}

{cross_mod_call}

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "{bt_name}", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("{bt_name}.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        {out_mo} resp = new {out_mo}();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }}

    public boolean checkTransactionEligibility(String correlationId) {{
        return this.dataGrabber.exists(correlationId);
    }}
}}
"""


def generate_elementary_transaction(mod_code: str, et_name: str, out_mo: str, primary_entity: str, dg_name: str) -> str:
    action_method = f"{et_name}Fetch"

    return f"""package com.tcs.bancs.{mod_code};

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: {et_name}
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class {et_name} {{

    private final {dg_name} dataGrabber;

    public {et_name}() {{
        this.dataGrabber = new {dg_name}();
    }}

    public {et_name}({dg_name} dataGrabber) {{
        this.dataGrabber = dataGrabber;
    }}

    /**
     * Primary Elementary Transaction fetch method: {action_method}
     */
    public {out_mo} {action_method}(String lookupKey) {{
        if (lookupKey == null || lookupKey.isBlank()) {{
            lookupKey = "INQUIRY_DEFAULT";
        }}

        {primary_entity} entity = this.dataGrabber.fetch{primary_entity}ById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "{et_name}", lookupKey, "FETCH");

        {out_mo} resp = new {out_mo}();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }}

    public boolean isHealthy() {{
        return this.dataGrabber != null;
    }}
}}
"""


def generate_batch_processor(mod_code: str, bp_name: str, bp_type: str, dg_name: str, svc_name: str) -> str:
    method_name = f"{bp_name}Process"

    badge_comment = {
        "PS": "Batch Processor (EOD / Periodic Execution)",
        "PB": "Process Before Batch (Pre-run validation)",
        "PA": "Process After Batch (Post-run reconciliation)"
    }.get(bp_type, "Batch Component")

    return f"""package com.tcs.bancs.{mod_code};

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS {badge_comment}: {bp_name}
 */
public class {bp_name} {{

    private final {dg_name} dataGrabber;
    private final {svc_name} service;
    private boolean isExecutionRunning = false;

    public {bp_name}() {{
        this.dataGrabber = new {dg_name}();
        this.service = new {svc_name}();
    }}

    public {bp_name}({dg_name} dataGrabber, {svc_name} service) {{
        this.dataGrabber = dataGrabber;
        this.service = service;
    }}

    /**
     * Primary batch execution method: {method_name}
     */
    public synchronized int {method_name}() {{
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {{
            AuditTrailService.logAuditEvent("BATCH_START", "{bp_name}", "{bp_type}", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("{bp_name}", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "{bp_name}", "{bp_type}", "PROCESSED=" + processedRecords);
        }} finally {{
            this.isExecutionRunning = false;
        }}
        return processedRecords;
    }}

    public boolean isRunning() {{
        return this.isExecutionRunning;
    }}
}}
"""


def generate_service(mod_code: str, svc_name: str, entity_name: str, dg_name: str) -> str:
    return f"""package com.tcs.bancs.{mod_code};

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: {svc_name}
 * Implements business calculation logic, validations, and domain rules.
 */
public class {svc_name} {{

    private final {dg_name} dataGrabber;

    public {svc_name}() {{
        this.dataGrabber = new {dg_name}();
    }}

    public {svc_name}({dg_name} dataGrabber) {{
        this.dataGrabber = dataGrabber;
    }}

    public boolean validateTransactionPreconditions(String contextId) {{
        if (contextId == null || contextId.isEmpty()) {{
            return false;
        }}
        return this.dataGrabber.exists(contextId);
    }}

    public double calculateInterestOrCharges(double baseAmount, double rate) {{
        if (baseAmount <= 0.0 || rate < 0.0) {{
            return 0.0;
        }}
        return (baseAmount * rate) / 100.0;
    }}

    public void executeBatchProcessingCycle(String batchName, int recordCount) {{
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "{svc_name}", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("{svc_name}." + batchName + ".records", (double) recordCount);
    }}

    public {entity_name} inspectAndReconcile(String entityId) {{
        {entity_name} entity = this.dataGrabber.fetch{entity_name}ById(entityId);
        if (entity != null) {{
            entity.Modify("RECONCILED");
        }}
        return entity;
    }}
}}
"""


def generate_controller(mod_code: str, ctrl_name: str, bt_name: str, et_name: str, inp_mo: str, out_mo: str) -> str:
    return f"""package com.tcs.bancs.{mod_code};

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: {ctrl_name}
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class {ctrl_name} {{

    private final {bt_name} businessTransaction;
    private final {et_name} elementaryTransaction;

    public {ctrl_name}() {{
        this.businessTransaction = new {bt_name}();
        this.elementaryTransaction = new {et_name}();
    }}

    public {ctrl_name}({bt_name} bt, {et_name} et) {{
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }}

    /**
     * Inbound mutating command handler.
     */
    public {out_mo} handleExecuteRequest({inp_mo} request) {{
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "{ctrl_name}", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.{bt_name}Execute(request);
    }}

    /**
     * Inbound read-only query handler.
     */
    public {out_mo} handleInquiryRequest(String queryKey) {{
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "{ctrl_name}", queryKey, "INQUIRY");
        return this.elementaryTransaction.{et_name}Fetch(queryKey);
    }}

    public boolean ping() {{
        return true;
    }}
}}
"""


def generate_enum(mod_code: str, enum_name: str, values: list) -> str:
    val_lines = ",\n    ".join(values)
    return f"""package com.tcs.bancs.{mod_code};

/**
 * TCS BaNCS Domain Enumeration: {enum_name}
 */
public enum {enum_name} {{
    {val_lines};

    public boolean isValid() {{
        return true;
    }}
}}
"""


def generate_record(mod_code: str, rec_name: str, fields: list) -> str:
    param_list = ", ".join(f"{ftype} {fname}" for ftype, fname in fields)
    return f"""package com.tcs.bancs.{mod_code};

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: {rec_name}
 */
public record {rec_name}({param_list}) implements Serializable {{
}}
"""


def generate_interface(mod_code: str, iface_name: str, methods: list) -> str:
    method_lines = ";\n    ".join(methods) + ";"
    return f"""package com.tcs.bancs.{mod_code};

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: {iface_name}
 */
public interface {iface_name} {{
    {method_lines}
}}
"""


def generate_common_files() -> dict:
    files = {}

    files["SecurityContext.java"] = """package com.tcs.bancs.common;

/**
 * Security context for BaNCS transaction execution.
 */
public class SecurityContext {
    private static final ThreadLocal<String> CURRENT_USER = ThreadLocal.withInitial(() -> "SYSTEM_USER");
    private static final ThreadLocal<String> CURRENT_BRANCH = ThreadLocal.withInitial(() -> "BR001");
    private static final ThreadLocal<String> CURRENT_TENANT = ThreadLocal.withInitial(() -> "DEFAULT_BANK");

    public static String getCurrentUser() { return CURRENT_USER.get(); }
    public static void setCurrentUser(String user) { CURRENT_USER.set(user); }
    public static String getCurrentBranch() { return CURRENT_BRANCH.get(); }
    public static void setCurrentBranch(String branch) { CURRENT_BRANCH.set(branch); }
    public static String getCurrentTenant() { return CURRENT_TENANT.get(); }
    public static void setCurrentTenant(String tenant) { CURRENT_TENANT.set(tenant); }
    public static void clear() { CURRENT_USER.remove(); CURRENT_BRANCH.remove(); CURRENT_TENANT.remove(); }
}
"""

    files["AuditTrailService.java"] = """package com.tcs.bancs.common;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Global audit logging framework for BaNCS domain transactions.
 */
public class AuditTrailService {
    private static final Queue<AuditEntryRecord> AUDIT_LOG = new ConcurrentLinkedQueue<>();

    public static void logAuditEvent(String category, String source, String correlationId, String detail) {
        AUDIT_LOG.offer(new AuditEntryRecord(category, source, correlationId, detail, System.currentTimeMillis()));
    }

    public static int getAuditLogSize() {
        return AUDIT_LOG.size();
    }

    public static void clear() {
        AUDIT_LOG.clear();
    }
}
"""

    files["EventDispatchService.java"] = """package com.tcs.bancs.common;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory domain event dispatcher.
 */
public class EventDispatchService {
    private static final Queue<DomainEventRecord> EVENTS = new ConcurrentLinkedQueue<>();

    public static void dispatchDomainEvent(String eventType, String entityId, String payload) {
        EVENTS.offer(new DomainEventRecord(UUID.randomUUID().toString(), eventType, entityId, System.currentTimeMillis()));
    }

    public static int getEventCount() {
        return EVENTS.size();
    }
}
"""

    files["TelemetryRecorder.java"] = """package com.tcs.bancs.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Telemetry and latency metric recorder.
 */
public class TelemetryRecorder {
    private static final Map<String, DoubleAdder> METRICS = new ConcurrentHashMap<>();

    public static void recordMetric(String metricName, double value) {
        METRICS.computeIfAbsent(metricName, k -> new DoubleAdder()).add(value);
    }

    public static double getMetric(String metricName) {
        DoubleAdder adder = METRICS.get(metricName);
        return adder != null ? adder.sum() : 0.0;
    }
}
"""

    files["BusinessException.java"] = """package com.tcs.bancs.common;

/**
 * Root business transaction exception for BaNCS.
 */
public class BusinessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
"""

    files["ValidationException.java"] = """package com.tcs.bancs.common;

/**
 * Input validation failure exception.
 */
public class ValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}
"""

    files["EntityNotFoundException.java"] = """package com.tcs.bancs.common;

/**
 * Entity not found exception.
 */
public class EntityNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EntityNotFoundException(String entityName, String id) {
        super("Entity " + entityName + " with key " + id + " was not found");
    }
}
"""

    files["TransactionChannel.java"] = """package com.tcs.bancs.common;

public enum TransactionChannel {
    BRANCH, MOBILE, INTERNET_BANKING, ATM, OPEN_API, SWIFT_HOST, BATCH_SCHEDULER
}
"""

    files["ExecutionStatus.java"] = """package com.tcs.bancs.common;

public enum ExecutionStatus {
    SUCCESS, PENDING, REJECTED, FAILED, TIMED_OUT, CANCELLED
}
"""

    files["RiskCategory.java"] = """package com.tcs.bancs.common;

public enum RiskCategory {
    LOW, MEDIUM, HIGH, PROHIBITED, CRITICAL
}
"""

    files["CurrencyCode.java"] = """package com.tcs.bancs.common;

public enum CurrencyCode {
    USD, EUR, GBP, JPY, INR, CHF, SGD, AUD, CAD, HKD
}
"""

    files["AuditEntryRecord.java"] = """package com.tcs.bancs.common;

import java.io.Serializable;

public record AuditEntryRecord(String category, String source, String correlationId, String detail, long timestamp) implements Serializable {
}
"""

    files["DomainEventRecord.java"] = """package com.tcs.bancs.common;

import java.io.Serializable;

public record DomainEventRecord(String eventId, String eventType, String entityId, long timestamp) implements Serializable {
}
"""

    files["MetricSnapshotRecord.java"] = """package com.tcs.bancs.common;

import java.io.Serializable;

public record MetricSnapshotRecord(String metricName, double value, long timestamp) implements Serializable {
}
"""

    files["AuditableEntity.java"] = """package com.tcs.bancs.common;

public interface AuditableEntity {
    String getEntityKey();
    long getLastModifiedTime();
}
"""

    files["EntityValidator.java"] = """package com.tcs.bancs.common;

public interface EntityValidator<T> {
    boolean isValid(T entity);
}
"""

    files["BatchLifecycleListener.java"] = """package com.tcs.bancs.common;

public interface BatchLifecycleListener {
    void onBatchStarted(String batchName);
    void onBatchCompleted(String batchName, int recordsProcessed);
}
"""

    files["BaseMoney.java"] = """package com.tcs.bancs.common;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class BaseMoney implements Serializable {
    private static final long serialVersionUID = 1L;
    private final BigDecimal amount;
    private final String currency;

    public BaseMoney(double amount, String currency) {
        this.amount = BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP);
        this.currency = currency != null ? currency : "USD";
    }

    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public double doubleValue() { return amount.doubleValue(); }
}
"""

    files["CorrelationContext.java"] = """package com.tcs.bancs.common;

import java.util.UUID;

public class CorrelationContext {
    private static final ThreadLocal<String> TRACE_ID = ThreadLocal.withInitial(() -> UUID.randomUUID().toString());

    public static String getTraceId() { return TRACE_ID.get(); }
    public static void setTraceId(String id) { TRACE_ID.set(id); }
    public static void reset() { TRACE_ID.set(UUID.randomUUID().toString()); }
}
"""

    files["SystemClock.java"] = """package com.tcs.bancs.common;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class SystemClock {
    public static LocalDate getBusinessDate() { return LocalDate.now(); }
    public static LocalDateTime getSystemDateTime() { return LocalDateTime.now(); }
    public static long getEpochMillis() { return System.currentTimeMillis(); }
}
"""

    return files


#!/usr/bin/env python3
"""
generate.py
Main generator driver for CodeLens load test suite.
Scaffolds 650+ Java classes, interfaces, enums, and records across 12 TCS BaNCS domains.
"""

import os
import sys
from pathlib import Path

# Add scratch to python path


DEST_ROOT = Path("/Volumes/Study/Projects/codelens/sample-project/src/main/java/com/tcs/bancs")

def ensure_dir(path: Path):
    path.mkdir(parents=True, exist_ok=True)

def write_java_file(rel_path: str, code: str):
    full_path = DEST_ROOT / rel_path
    ensure_dir(full_path.parent)
    with open(full_path, "w", encoding="utf-8") as f:
        f.write(code.strip() + "\n")

def main():
    print("=" * 70)
    print("Starting TCS BaNCS Enterprise Codebase Generation...")
    print("=" * 70)

    total_files_generated = 0
    module_breakup = {}

    # 1. Generate common framework files
    common_files = generate_common_files()
    for fname, code in common_files.items():
        write_java_file(f"common/{fname}", code)
        total_files_generated += 1
    module_breakup["common"] = len(common_files)
    print(f"  [+] Generated common module: {len(common_files)} files")

    # 2. Generate each of the 12 domain modules
    for mod_code, mod_meta in MODULES.items():
        mod_name = mod_meta["name"]
        cross_deps = mod_meta.get("cross_deps", [])
        primary_cross = cross_deps[0] if cross_deps else "common"

        p_classes = mod_meta["persistent_classes"]
        mos = mod_meta["message_objects"]
        dgs = mod_meta["data_grabbers"]
        bts = mod_meta["business_transactions"]
        ets = mod_meta["elementary_transactions"]
        bps = mod_meta["batch_processors"]
        services = mod_meta["services"]
        controllers = mod_meta["controllers"]
        enums = mod_meta["enums"]
        records = mod_meta["records"]
        interfaces = mod_meta["interfaces"]

        primary_entity_name = p_classes[0]["name"]
        primary_dg_name = dgs[0]
        primary_svc_name = services[0]
        primary_summary_mo = mos[-2][0] if len(mos) >= 2 else mos[0][0]
        primary_inp_mo = mos[0][0]
        primary_out_mo = mos[1][0] if len(mos) > 1 else mos[0][0]

        count = 0

        # A. Persistent Classes (4)
        for p_entity in p_classes:
            code = generate_persistent_class(mod_code, p_entity)
            write_java_file(f"{mod_code}/{p_entity['name']}.java", code)
            count += 1

        # B. Message Objects (12)
        for mo_name, fields in mos:
            code = generate_message_object(mod_code, mo_name, fields)
            write_java_file(f"{mod_code}/{mo_name}.java", code)
            count += 1

        # C. Data Grabbers (4)
        for i, dg_name in enumerate(dgs):
            target_entity = p_classes[i % len(p_classes)]["name"]
            code = generate_data_grabber(mod_code, dg_name, target_entity, primary_summary_mo)
            write_java_file(f"{mod_code}/{dg_name}.java", code)
            count += 1

        # D. Business Transactions (5)
        for i, bt_name in enumerate(bts):
            inp = mos[(i * 2) % len(mos)][0]
            out = mos[(i * 2 + 1) % len(mos)][0]
            target_entity = p_classes[i % len(p_classes)]["name"]
            target_dg = dgs[i % len(dgs)]
            target_svc = services[i % len(services)]
            code = generate_business_transaction(mod_code, bt_name, inp, out, target_entity, target_dg, target_svc, primary_cross)
            write_java_file(f"{mod_code}/{bt_name}.java", code)
            count += 1

        # E. Elementary Transactions (4)
        for i, et_name in enumerate(ets):
            out = mos[(i * 2 + 1) % len(mos)][0]
            target_entity = p_classes[i % len(p_classes)]["name"]
            target_dg = dgs[i % len(dgs)]
            code = generate_elementary_transaction(mod_code, et_name, out, target_entity, target_dg)
            write_java_file(f"{mod_code}/{et_name}.java", code)
            count += 1

        # F. Batch Processors (3: PS, PB, PA)
        bp_types = ["PS", "PB", "PA"]
        for i, bp_name in enumerate(bps):
            bp_type = bp_types[i % len(bp_types)]
            target_dg = dgs[0]
            target_svc = services[0]
            code = generate_batch_processor(mod_code, bp_name, bp_type, target_dg, target_svc)
            write_java_file(f"{mod_code}/{bp_name}.java", code)
            count += 1

        # G. Services (6)
        for i, svc_name in enumerate(services):
            target_entity = p_classes[i % len(p_classes)]["name"]
            target_dg = dgs[i % len(dgs)]
            code = generate_service(mod_code, svc_name, target_entity, target_dg)
            write_java_file(f"{mod_code}/{svc_name}.java", code)
            count += 1

        # H. Controllers (3)
        for i, ctrl_name in enumerate(controllers):
            target_bt = bts[i % len(bts)]
            target_et = ets[i % len(ets)]
            inp = mos[(i * 2) % len(mos)][0]
            out = mos[(i * 2 + 1) % len(mos)][0]
            code = generate_controller(mod_code, ctrl_name, target_bt, target_et, inp, out)
            write_java_file(f"{mod_code}/{ctrl_name}.java", code)
            count += 1

        # I. Enums (5)
        for enum_name, values in enums:
            code = generate_enum(mod_code, enum_name, values)
            write_java_file(f"{mod_code}/{enum_name}.java", code)
            count += 1

        # J. Records (4)
        for rec_name, fields in records:
            code = generate_record(mod_code, rec_name, fields)
            write_java_file(f"{mod_code}/{rec_name}.java", code)
            count += 1

        # K. Interfaces (3)
        for iface_name, methods in interfaces:
            code = generate_interface(mod_code, iface_name, methods)
            write_java_file(f"{mod_code}/{iface_name}.java", code)
            count += 1

        module_breakup[mod_code] = count
        total_files_generated += count
        print(f"  [+] Generated module {mod_code} ({mod_name}): {count} files")

    print("=" * 70)
    print(f"Total files generated: {total_files_generated}")
    print("Module Breakup:")
    for m, c in module_breakup.items():
        print(f"  - {m}: {c} classes/records/interfaces")
    print("=" * 70)

if __name__ == "__main__":
    main()

