"""Verify the recovery-only visual seed against a disposable Room database copy."""

from __future__ import annotations

import shutil
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DB = ROOT / ".build-output/new-device-data/preseed-20260821-consistent.db"
SEED_SQL = ROOT / "scripts/seed-recovery-visual-data.sql"
EXPECTED_MONTHS = [
    "2025-09", "2025-10", "2025-11", "2025-12",
    "2026-01", "2026-02", "2026-03", "2026-04",
    "2026-05", "2026-06", "2026-07", "2026-08",
]


def snapshot(connection: sqlite3.Connection) -> tuple[int, list[tuple[str, int, int, int, int]]]:
    count = connection.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    months = connection.execute(
        """
        SELECT strftime('%Y-%m', occurredAt / 1000, 'unixepoch', 'localtime') AS month,
               COUNT(*),
               SUM(CASE WHEN type = 'INCOME' THEN amountCents ELSE 0 END),
               SUM(CASE WHEN type IN ('EXPENSE', 'FEE') THEN amountCents ELSE 0 END)
                 - SUM(CASE WHEN type = 'REFUND' THEN amountCents ELSE 0 END)
                 - SUM(CASE WHEN type = 'EXPENSE' THEN reimbursedCents ELSE 0 END),
               SUM(CASE WHEN type IN ('LOAN_PAYMENT', 'LOAN_PREPAYMENT') THEN amountCents ELSE 0 END)
        FROM transactions
        GROUP BY month
        ORDER BY month
        """
    ).fetchall()
    return count, months


def main() -> None:
    if not SOURCE_DB.is_file():
        raise SystemExit(f"missing source database: {SOURCE_DB}")

    with tempfile.TemporaryDirectory(prefix="assets-king-visual-seed-") as temp_dir:
        database = Path(temp_dir) / "assets-king.db"
        shutil.copy2(SOURCE_DB, database)
        sql = SEED_SQL.read_text(encoding="utf-8")

        # sqlite3.Connection's context manager commits/rolls back but does not close.
        # Explicit closing is required so Windows can remove the disposable database.
        with closing(sqlite3.connect(database)) as connection:
            connection.executescript(sql)
            first = snapshot(connection)
            connection.executescript(sql)
            second = snapshot(connection)

            assert first == second, "seed is not idempotent"
            assert first[0] == 109, f"expected 109 transactions, got {first[0]}"
            assert [row[0] for row in first[1]] == EXPECTED_MONTHS
            assert all(row[2] > 0 and row[3] > 0 for row in first[1])
            assert all(row[4] > 0 for row in first[1]), "every month must include an actual repayment"
            by_month = {row[0]: row for row in first[1]}
            assert by_month["2026-08"][2:] == (698_983, 320_350, 342_000)
            assert by_month["2026-08"][2] - by_month["2026-08"][3] - by_month["2026-08"][4] == 36_633
            assert by_month["2026-07"][4] == 342_000
            assert by_month["2026-06"][2] - by_month["2026-06"][3] - by_month["2026-06"][4] == -412_100
            assert connection.execute("PRAGMA quick_check").fetchone()[0] == "ok"
            assert connection.execute("PRAGMA foreign_key_check").fetchall() == []

        print("PASS: 109 transactions across 12 non-zero months; reimbursement states visible; every month has repayment; deficit fixture present; idempotent; database checks clean")
        for month, count, income, expense, repayment in first[1]:
            balance = income - expense - repayment
            print(
                f"{month}: {count:2d} rows, income={income:7d}, expense={expense:7d}, "
                f"repayment={repayment:7d}, balance={balance:7d}"
            )


if __name__ == "__main__":
    main()
