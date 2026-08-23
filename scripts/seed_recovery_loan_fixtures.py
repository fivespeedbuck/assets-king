"""Idempotently seed recovery-only loan fixtures into a stopped app database.

This script is intentionally outside the Android app. It must only be used on a
pulled database from com.assetsking.app.recovery; formal builds start empty and
must never run this fixture seeder.
"""

from __future__ import annotations

import json
import sqlite3
import sys
from datetime import date, datetime, timezone
from pathlib import Path


def epoch_day(value: str) -> int:
    return (date.fromisoformat(value) - date(1970, 1, 1)).days


def installment(number: int, due: str, principal: int, interest: int, status: str = "UPCOMING") -> dict:
    return {
        "number": number,
        "dueDateEpochDay": epoch_day(due),
        "principal": principal,
        "interest": interest,
        "fee": 0,
        "status": status,
    }


def upsert_loan_account(connection: sqlite3.Connection, account_id: str, name: str, balance: int, due_day: int, start: str) -> None:
    connection.execute(
        """
        INSERT OR REPLACE INTO accounts(
            id,name,type,balanceCents,cardTail,balanceStatus,lastCheckedAt,groupName,
            statementDay,dueDay,creditLimitCents,statementOriginalDueCents,pendingCents,
            archived,startDateEpochDay
        ) VALUES(?,?, 'LOAN', ?, NULL, 'UNCHECKED', NULL, '负债', NULL, ?, 0, 0, 0, 0, ?)
        """,
        (account_id, name, balance, due_day, epoch_day(start)),
    )


def upsert_loan_plan(
    connection: sqlite3.Connection,
    plan_id: str,
    account_id: str,
    principal: int,
    start: str,
    method: str,
    installments: list[dict],
    annual_rate_bps: int,
    remaining_principal: int,
    due_day: int,
) -> None:
    connection.execute(
        """
        INSERT OR REPLACE INTO loan_plans(
            id,accountId,principalCents,startDateEpochDay,repaymentMethod,installmentsJson,
            annualRateBps,remainingPrincipalCents,earlyRepaidCents,repaymentDay,status
        ) VALUES(?,?,?,?,?,?,?,?,0,?,'ACTIVE')
        """,
        (
            plan_id,
            account_id,
            principal,
            epoch_day(start),
            method,
            json.dumps(installments, ensure_ascii=False, separators=(",", ":")),
            annual_rate_bps,
            remaining_principal,
            due_day,
        ),
    )


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: seed_recovery_loan_fixtures.py <assets-king.db>")
    database_path = Path(sys.argv[1]).resolve()
    if database_path.name != "assets-king.db" or "recovery" not in str(database_path).lower():
        raise SystemExit("refusing to seed a database without an explicit recovery path")

    connection = sqlite3.connect(database_path)
    connection.execute("PRAGMA foreign_keys=ON")
    connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")

    with connection:
        fixture_plan_ids = (
            "fixture-ningbo-interest-only",
            "fixture-zhaolian-equal-payment",
            "fixture-month-layout",
        )
        fixture_account_ids = (
            "fixture-ningbo-loan",
            "fixture-zhaolian-loan",
            "fixture-month-layout-loan",
        )
        connection.executemany("DELETE FROM loan_plans WHERE id=?", [(value,) for value in fixture_plan_ids])
        connection.executemany("DELETE FROM accounts WHERE id=?", [(value,) for value in fixture_account_ids])

        # 可操作的信用分期样本：同时满足“单笔消费分期”和“账单分期”的入口条件。
        # 正式包不会运行本脚本；这些记录只存在于 recovery 真机验收库。
        fixture_credit_id = "fixture-credit-card"
        fixture_credit_transactions = ("fixture-credit-expense-1", "fixture-credit-expense-2", "fixture-credit-expense-3")
        connection.executemany("DELETE FROM transactions WHERE id=?", [(value,) for value in fixture_credit_transactions])
        connection.execute("DELETE FROM accounts WHERE id=?", (fixture_credit_id,))
        connection.execute(
            """
            INSERT INTO accounts(
                id,name,type,balanceCents,cardTail,balanceStatus,lastCheckedAt,groupName,
                statementDay,dueDay,creditLimitCents,statementOriginalDueCents,pendingCents,
                archived,startDateEpochDay
            ) VALUES(?,?,'CREDIT',150000,'3304','CHECKED',NULL,'负债',26,15,3000000,150000,0,0,?)
            """,
            (fixture_credit_id, "广发信用卡（分期测试）", epoch_day("2026-08-01")),
        )
        transaction_rows = [
            ("fixture-credit-expense-1", 36_000, "淘宝模拟消费", "2026-08-10"),
            ("fixture-credit-expense-2", 64_000, "京东模拟消费", "2026-08-12"),
            ("fixture-credit-expense-3", 50_000, "日常模拟消费", "2026-08-20"),
        ]
        connection.executemany(
            """
            INSERT INTO transactions(
                id,accountId,amountCents,type,category,occurredAt,merchant,note,status,
                isReimbursable,recurringRuleId,principalCents,interestCents,feeCents,
                loanPlanId,refundOfId,reimbursedCents,necessity,channel,notificationId
            ) VALUES(?, ?, ?, 'EXPENSE', '其他支出', ?, ?, 'recovery信用分期交互样本',
                     'CONFIRMED', 0, NULL, 0, 0, 0, NULL, NULL, 0, 0, '模拟', NULL)
            """,
            [
                (
                    transaction_id,
                    fixture_credit_id,
                    amount,
                    int(datetime.fromisoformat(occurred).replace(tzinfo=timezone.utc).timestamp() * 1000),
                    merchant,
                )
                for transaction_id, amount, merchant, occurred in transaction_rows
            ],
        )

        ningbo = [
            installment(1, "2026-06-23", 0, 30_595, "PAID"),
            installment(2, "2026-07-23", 0, 31_650, "PAID"),
            installment(3, "2026-08-23", 0, 32_705, "PAID"),
            installment(4, "2026-09-23", 0, 32_705),
            installment(5, "2026-10-23", 0, 31_650),
            installment(6, "2026-11-23", 0, 32_705),
            installment(7, "2026-12-23", 0, 31_650),
            installment(8, "2027-01-23", 0, 32_705),
            installment(9, "2027-02-23", 0, 32_705),
            installment(10, "2027-03-23", 0, 29_540),
            installment(11, "2027-04-23", 0, 32_705),
            installment(12, "2027-05-23", 5_000_000, 31_650),
        ]
        upsert_loan_account(connection, "fixture-ningbo-loan", "宁波银行先息后本（模拟）", 5_000_000, 23, "2026-05-25")
        upsert_loan_plan(
            connection,
            "fixture-ningbo-interest-only",
            "fixture-ningbo-loan",
            5_000_000,
            "2026-05-25",
            "INTEREST_ONLY",
            ningbo,
            0,
            5_000_000,
            23,
        )

        principal_yuan = [
            1194.74,1246.63,1244.40,1263.07,1261.50,1270.10,1306.82,1287.67,1305.24,
            1305.36,1322.47,1323.28,1332.30,1348.72,1350.59,1366.54,1369.12,1378.46,
            1399.53,1397.40,1412.15,1416.56,1430.82,1435.98,1445.78,1459.29,1465.59,
            1478.59,1485.67,1495.80,1512.07,1516.31,1528.01,1537.08,1548.24,1558.12,
        ]
        interest_yuan = [
            374.00,322.11,324.34,305.67,307.24,298.64,261.92,281.07,263.50,263.38,
            246.27,245.46,236.44,220.02,218.15,202.20,199.62,190.28,169.21,171.34,
            156.59,152.18,137.92,132.76,122.96,109.45,103.15,90.15,83.07,72.94,
            56.67,52.43,40.73,31.66,20.50,10.62,
        ]
        zhaolian = []
        first_due = date(2026, 9, 17)
        for index, (principal_value, interest_value) in enumerate(zip(principal_yuan, interest_yuan, strict=True)):
            year = first_due.year + (first_due.month - 1 + index) // 12
            month = (first_due.month - 1 + index) % 12 + 1
            zhaolian.append(
                installment(
                    index + 1,
                    date(year, month, 17).isoformat(),
                    round(principal_value * 100),
                    round(interest_value * 100),
                )
            )
        assert len(zhaolian) == 36
        assert sum(item["principal"] for item in zhaolian) == 5_000_000
        assert sum(item["interest"] for item in zhaolian) == 647_464
        assert all(item["principal"] + item["interest"] == 156_874 for item in zhaolian)
        upsert_loan_account(connection, "fixture-zhaolian-loan", "招联消费贷（模拟）", 5_000_000, 17, "2026-08-14")
        upsert_loan_plan(
            connection,
            "fixture-zhaolian-equal-payment",
            "fixture-zhaolian-loan",
            5_000_000,
            "2026-08-14",
            "EQUAL_PAYMENT",
            zhaolian,
            803,
            5_000_000,
            17,
        )

        month_layout = [
            installment(1, "2026-08-18", 100_000, 6_000),
            installment(2, "2026-08-23", 100_000, 5_000, "PAID"),
            installment(3, "2026-08-27", 100_000, 4_000),
            installment(4, "2026-09-27", 100_000, 3_000),
        ]
        upsert_loan_account(connection, "fixture-month-layout-loan", "本月排版测试贷（模拟）", 300_000, 27, "2026-07-18")
        upsert_loan_plan(
            connection,
            "fixture-month-layout",
            "fixture-month-layout-loan",
            400_000,
            "2026-07-18",
            "EQUAL_PRINCIPAL",
            month_layout,
            720,
            300_000,
            27,
        )

        # Remove the v22 legacy preview row and replace it with a complete, auditable active fixture.
        for plan_id in ("visual-card-installment", "fixture-card-installment-active"):
            connection.execute("DELETE FROM credit_card_installment_payment_matches WHERE planId=?", (plan_id,))
            connection.execute("DELETE FROM credit_card_installment_schedules WHERE planId=?", (plan_id,))
            connection.execute("DELETE FROM credit_card_installment_allocations WHERE planId=?", (plan_id,))
            connection.execute("DELETE FROM credit_card_installment_audit_events WHERE planId=?", (plan_id,))
            connection.execute("DELETE FROM credit_card_installments WHERE id=?", (plan_id,))

        now_millis = int(datetime.now(timezone.utc).timestamp() * 1000)
        card_plan_id = "fixture-card-installment-active"
        connection.execute(
            """
            INSERT INTO credit_card_installments(
                id,cardAccountId,label,originalPrincipalCents,remainingPrincipalCents,
                monthlyPaymentCents,feeCentsPerPeriod,periodsRemaining,startDateEpochDay,
                installmentType,installmentCount,nextDueDateEpochDay,status,scheduleRevision,
                createdAt,updatedAt,statementCycleStartEpochDay
            ) VALUES(?,?,?,?,?,?,?,?,?,'POST_PURCHASE_INSTALLMENT',?,?, 'ACTIVE',1,?,?,NULL)
            """,
            (
                card_plan_id,"cgb","模拟手机分期（3期免息）",24_990,16_660,8_330,0,2,
                epoch_day("2026-08-01"),3,epoch_day("2026-09-15"),now_millis,now_millis,
            ),
        )
        connection.execute(
            "INSERT INTO credit_card_installment_allocations(planId,transactionId,allocatedPrincipalCents,createdAt) VALUES(?,?,?,?)",
            (card_plan_id,"visual-takeout",24_990,now_millis),
        )
        card_rows = [
            (1,"2026-08-15",8_330,8_330,"PAID"),
            (2,"2026-09-15",8_330,0,"UPCOMING"),
            (3,"2026-10-15",8_330,0,"UPCOMING"),
        ]
        connection.executemany(
            """
            INSERT INTO credit_card_installment_schedules(
                id,planId,revision,number,dueDateEpochDay,principalDueCents,
                expectedInterestCents,expectedFeeCents,expectedUnclassifiedChargeCents,
                principalPaidCents,interestPaidCents,feePaidCents,status
            ) VALUES(?,?,1,?,?,?,0,0,0,?,0,0,?)
            """,
            [
                (f"{card_plan_id}-r1-{number}",card_plan_id,number,epoch_day(due),principal,paid,status)
                for number,due,principal,paid,status in card_rows
            ],
        )
        connection.execute(
            "INSERT INTO credit_card_installment_audit_events(id,planId,eventType,occurredAt,source,payloadJson) VALUES(?,?,?,?,?,?)",
            (f"{card_plan_id}-created",card_plan_id,"CREATED",now_millis,"RECOVERY_FIXTURE",json.dumps({"fixture": True})),
        )

    connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    connection.close()


if __name__ == "__main__":
    main()
