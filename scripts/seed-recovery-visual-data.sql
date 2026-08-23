-- 仅用于 com.assetsking.app.recovery 独立验收包。
-- 执行前必须强制停止 App 并备份 databases/assets-king.db。
PRAGMA foreign_keys = OFF;
BEGIN IMMEDIATE;

DELETE FROM reimbursement_links;
DELETE FROM transfers;
DELETE FROM transactions;
DELETE FROM budgets;
DELETE FROM credit_card_installments;
DELETE FROM loan_plans;
DELETE FROM balance_checkpoints;

-- 资产约 4,700，总欠款约 100,000：信用卡 30,000 + 消费贷 70,000。
-- 时间戳显式带 +08:00，避免 SQLite 把本地墙钟当 UTC 后在真机显示时再偏移 8 小时。
-- 招行尾号 3683 是恢复包专用测试值，用于覆盖银行消息选错账户校验；不代表恢复旧正式账本。
UPDATE accounts SET balanceCents = 420000, cardTail = '3683', balanceStatus = 'VERIFIED', lastCheckedAt = strftime('%s','2026-08-20T20:00:00+08:00') * 1000 WHERE id = 'cmb';
UPDATE accounts SET balanceCents = 50000, balanceStatus = 'VERIFIED', lastCheckedAt = strftime('%s','2026-08-20T20:00:00+08:00') * 1000 WHERE id = 'nbcb';
UPDATE accounts SET balanceCents = 3000000, statementOriginalDueCents = 32705, statementDay = 8, dueDay = 23, creditLimitCents = 5000000, balanceStatus = 'VERIFIED', lastCheckedAt = strftime('%s','2026-08-20T20:00:00+08:00') * 1000 WHERE id = 'cgb';
UPDATE accounts SET balanceCents = 0, statementOriginalDueCents = 0, balanceStatus = 'VERIFIED', lastCheckedAt = strftime('%s','2026-08-20T20:00:00+08:00') * 1000 WHERE id = 'huabei';
INSERT OR REPLACE INTO accounts(id,name,type,balanceCents,cardTail,balanceStatus,lastCheckedAt,groupName,statementDay,dueDay,creditLimitCents,statementOriginalDueCents,pendingCents,archived,startDateEpochDay)
VALUES('visual-loan','招行消费贷','LOAN',7000000,NULL,'VERIFIED',strftime('%s','2026-08-20T20:00:00+08:00') * 1000,'贷款',NULL,23,0,0,0,0,CAST(strftime('%s','2026-01-01') / 86400 AS INTEGER));

INSERT INTO balance_checkpoints(id,accountId,balanceCents,checkedAt,source) VALUES
('visual-cp-cmb','cmb',420000,strftime('%s','2026-08-20T20:00:00+08:00') * 1000,'MANUAL'),
('visual-cp-nbcb','nbcb',50000,strftime('%s','2026-08-20T20:00:00+08:00') * 1000,'MANUAL'),
('visual-cp-cgb','cgb',3000000,strftime('%s','2026-08-20T20:00:00+08:00') * 1000,'MANUAL'),
('visual-cp-huabei','huabei',0,strftime('%s','2026-08-20T20:00:00+08:00') * 1000,'MANUAL'),
('visual-cp-loan','visual-loan',7000000,strftime('%s','2026-08-20T20:00:00+08:00') * 1000,'MANUAL');

-- 当月分类预算，覆盖必要与非必要进度条。
INSERT INTO budgets(id,category,monthlyLimitCents,month) VALUES
('visual-budget-rent','房租',150000,'2026-08'),
('visual-budget-grocery','买菜',45000,'2026-08'),
('visual-budget-dine','堂食',35000,'2026-08'),
('visual-budget-takeout','外卖',30000,'2026-08'),
('visual-budget-metro','地铁',12000,'2026-08'),
('visual-budget-cat','猫粮',18000,'2026-08'),
('visual-budget-books','书籍资料',15000,'2026-08'),
('visual-budget-fitness','运动补剂',10000,'2026-08');

-- 过去 11 个完整自然月：每月 1 笔工资 + 6 类正常消费 + 1 笔实际还款。
-- 加上下面的 2026-08 当前月与两笔趋势专项流水，共形成连续 12 个月、109 条流水；既能让流水页有足够密度，
-- 又能让统计页 3/6/12 月档位都在真实非零数据上验收。所有事件都早于 2026-08-20
-- 权威余额检查点，不改变当前账户余额。
WITH RECURSIVE months(monthOffset, month) AS (
    SELECT 1, strftime('%Y-%m', date('2026-08-01', '-1 month'))
    UNION ALL
    SELECT monthOffset + 1,
           strftime('%Y-%m', date('2026-08-01', printf('-%d months', monthOffset + 1)))
    FROM months
    WHERE monthOffset < 11
), history(
    month, monthOffset, suffix, accountId, amountCents, type, category,
    dayTime, merchant, noteLabel, necessity, channel
) AS (
    SELECT month, monthOffset, 'salary', 'cmb',
           665000 + ((12 - monthOffset) % 4) * 8500,
           'INCOME', '工资', '05T08:41:00', '公司工资', '工资', 1, '招商银行'
    FROM months
    UNION ALL
    SELECT month, monthOffset, 'rent', 'cmb', 150000,
           'EXPENSE', '房租', '01T09:00:00', '房东', '房租', 1, '微信'
    FROM months
    UNION ALL
    SELECT month, monthOffset, 'grocery', 'cmb',
           30000 + (monthOffset % 5) * 2600,
           'EXPENSE', '买菜', '07T18:20:00', '盒马鲜生', '日常食材', 1, '支付宝'
    FROM months
    UNION ALL
    SELECT month, monthOffset, 'dine', 'nbcb',
           18000 + (monthOffset % 4) * 2300,
           'EXPENSE', '堂食', '12T12:26:00', '全家便利店', '工作餐', 1, '微信'
    FROM months
    UNION ALL
    SELECT month, monthOffset, 'taxi', 'cgb',
           6800 + (monthOffset % 5) * 1200,
           'EXPENSE', '打车', '16T22:08:00', '滴滴出行', '晚间出行', 0, '支付宝'
    FROM months
    UNION ALL
    SELECT month, monthOffset, 'phone', 'cmb',
           6000 + (monthOffset % 4) * 850,
           'EXPENSE', '手机话费', '21T09:18:00', '中国移动', '手机话费', 1, '支付宝'
    FROM months
    UNION ALL
    SELECT month, monthOffset, 'leisure', 'cgb',
           5600 + (monthOffset % 6) * 900,
           'EXPENSE',
           CASE WHEN monthOffset % 2 = 0 THEN '书籍资料' ELSE '电影演出' END,
           '26T20:10:00',
           CASE WHEN monthOffset % 2 = 0 THEN '当当网' ELSE '万达影城' END,
           CASE WHEN monthOffset % 2 = 0 THEN '专业书籍' ELSE '周末电影' END,
           0, '微信'
    FROM months
    UNION ALL
    SELECT month, monthOffset, 'repayment', 'cmb', 342000,
           'LOAN_PAYMENT', '贷款还款', '23T09:30:00', '历史贷款', '月度还款', 1, '招商银行'
    FROM months
)
INSERT INTO transactions(
    id,accountId,amountCents,type,category,occurredAt,merchant,note,status,
    isReimbursable,recurringRuleId,principalCents,interestCents,feeCents,
    loanPlanId,refundOfId,reimbursedCents,necessity,channel,notificationId
)
SELECT
    'visual-history-' || suffix || '-' || month,
    accountId,
    amountCents,
    type,
    category,
    strftime('%s', month || '-' || dayTime || '+08:00') * 1000,
    merchant,
    substr(month, 6, 2) || '月' || noteLabel,
    'CONFIRMED',
    0,NULL,0,0,0,NULL,NULL,0,necessity,channel,NULL
FROM history;

-- 当前月工资 + 多分类消费 + 退款 + 报销 + 贷款还款，用于验证全部列表和统计状态。
INSERT INTO transactions(id,accountId,amountCents,type,category,occurredAt,merchant,note,status,isReimbursable,recurringRuleId,principalCents,interestCents,feeCents,loanPlanId,refundOfId,reimbursedCents,necessity,channel,notificationId) VALUES
('visual-salary','cmb',695095,'INCOME','工资',strftime('%s','2026-08-05T08:41:00+08:00') * 1000,'公司工资','8月工资','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'招商银行',NULL),
('visual-interest','cmb',3888,'INCOME','利息收益',strftime('%s','2026-08-18T09:12:00+08:00') * 1000,'招商银行','活期利息','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'招商银行',NULL),
('visual-rent','cmb',150000,'EXPENSE','房租',strftime('%s','2026-08-01T09:00:00+08:00') * 1000,'房东','8月房租','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'微信',NULL),
('visual-grocery','cmb',42860,'EXPENSE','买菜',strftime('%s','2026-08-03T18:20:00+08:00') * 1000,'盒马鲜生','一周食材','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'支付宝',NULL),
('visual-dine','nbcb',32140,'EXPENSE','堂食',strftime('%s','2026-08-06T12:26:00+08:00') * 1000,'全家便利店','午餐','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'微信',NULL),
('visual-takeout','cgb',24990,'EXPENSE','外卖',strftime('%s','2026-08-08T19:35:00+08:00') * 1000,'美团外卖','晚餐','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,0,'美团',NULL),
('visual-takeout-refund','cgb',2800,'REFUND','外卖',strftime('%s','2026-08-09T10:15:00+08:00') * 1000,'美团外卖','缺餐退款','CONFIRMED',0,NULL,0,0,0,NULL,'visual-takeout',0,0,'美团',NULL),
('visual-taxi','cgb',12600,'EXPENSE','打车',strftime('%s','2026-08-10T22:08:00+08:00') * 1000,'滴滴出行','加班回家','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,0,'支付宝',NULL),
('visual-metro','nbcb',5700,'EXPENSE','地铁',strftime('%s','2026-08-11T08:34:00+08:00') * 1000,'宁波地铁','通勤','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'云闪付',NULL),
('visual-cat','cmb',15600,'EXPENSE','猫粮',strftime('%s','2026-08-12T20:02:00+08:00') * 1000,'宠物生活馆','主粮补货','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'微信',NULL),
-- 同一笔 128 元书籍垫付拆成 78 元未报销 + 50 元已报销，只增加呈现行，不改变统计总额与现金流口径。
('visual-books','cmb',7800,'EXPENSE','书籍资料',strftime('%s','2026-08-13T14:32:00+08:00') * 1000,'当当网','专业书籍（待报销）','CONFIRMED',1,NULL,0,0,0,NULL,NULL,0,0,'支付宝',NULL),
('visual-books-settled','cmb',5000,'EXPENSE','书籍资料',strftime('%s','2026-08-13T14:33:00+08:00') * 1000,'当当网','专业书籍（已报销）','CONFIRMED',1,NULL,0,0,0,NULL,NULL,5000,0,'支付宝',NULL),
('visual-reimbursement','cmb',5000,'REIMBURSEMENT','书籍资料',strftime('%s','2026-08-19T16:20:00+08:00') * 1000,'公司报销','书籍报销到账','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'招商银行',NULL),
('visual-fitness','cgb',8800,'EXPENSE','运动补剂',strftime('%s','2026-08-14T19:06:00+08:00') * 1000,'迪卡侬','蛋白粉补剂','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,0,'支付宝',NULL),
('visual-movie','cgb',7600,'EXPENSE','电影演出',strftime('%s','2026-08-15T20:10:00+08:00') * 1000,'万达影城','周末电影','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,0,'微信',NULL),
('visual-phone','cmb',6800,'EXPENSE','手机话费',strftime('%s','2026-08-16T09:18:00+08:00') * 1000,'中国移动','8月话费','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'支付宝',NULL),
('visual-electricity','cmb',4120,'EXPENSE','电费',strftime('%s','2026-08-17T11:05:00+08:00') * 1000,'国网电力','8月电费','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'支付宝',NULL),
('visual-water','cmb',1940,'EXPENSE','水费',strftime('%s','2026-08-18T11:06:00+08:00') * 1000,'自来水公司','8月水费','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'支付宝',NULL),
('visual-daily','nbcb',2200,'EXPENSE','日用品',strftime('%s','2026-08-20T10:16:00+08:00') * 1000,'罗森便利店','纸巾','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,1,'微信',NULL),
-- 当前月实际提前还款：让组成柱稳定出现红色普通支出、紫色还款与蓝色现金结余三段。
('visual-loan-prepayment-aug','cmb',342000,'LOAN_PREPAYMENT','贷款还款',strftime('%s','2026-08-18T18:30:00+08:00') * 1000,'招行消费贷','提前还款','CONFIRMED',0,NULL,342000,0,0,'visual-loan',NULL,0,1,'招商银行',NULL),
-- 6 月大额消费：普通支出本身超过当月收入，专门验收无蓝段且明确显示现金赤字。
('visual-overspend-jun','cgb',520000,'EXPENSE','数码家电',strftime('%s','2026-06-18T15:20:00+08:00') * 1000,'京东商城','电脑购置（赤字月验收）','CONFIRMED',0,NULL,0,0,0,NULL,NULL,0,0,'京东支付',NULL);

INSERT INTO reimbursement_links(reimbursementTxId,expenseTxId,coveredCents)
VALUES('visual-reimbursement','visual-books-settled',5000);

INSERT INTO transfers(id,fromAccountId,toAccountId,amountCents,occurredAt,note)
VALUES('visual-transfer-card','cmb','cgb',200000,strftime('%s','2026-08-18T09:15:00+08:00') * 1000,'信用卡还款');

INSERT INTO loan_plans(id,accountId,principalCents,startDateEpochDay,repaymentMethod,installmentsJson,annualRateBps,remainingPrincipalCents,earlyRepaidCents,repaymentDay,status)
VALUES(
  'visual-loan','visual-loan',8000000,CAST(strftime('%s','2026-07-01') / 86400 AS INTEGER),'EQUAL_PAYMENT',
  '[{"number":1,"dueDateEpochDay":20657,"principal":300000,"interest":42000,"fee":0,"status":"PAID"},{"number":2,"dueDateEpochDay":20688,"principal":305000,"interest":37000,"fee":0,"status":"UPCOMING"},{"number":3,"dueDateEpochDay":20719,"principal":310000,"interest":32000,"fee":0,"status":"UPCOMING"},{"number":4,"dueDateEpochDay":20749,"principal":315000,"interest":27000,"fee":0,"status":"UPCOMING"},{"number":5,"dueDateEpochDay":20780,"principal":320000,"interest":22000,"fee":0,"status":"UPCOMING"},{"number":6,"dueDateEpochDay":20810,"principal":325000,"interest":17000,"fee":0,"status":"UPCOMING"}]',
  720,7000000,1000000,23,'ACTIVE'
);

INSERT INTO credit_card_installments(id,cardAccountId,label,originalPrincipalCents,remainingPrincipalCents,monthlyPaymentCents,feeCentsPerPeriod,periodsRemaining,startDateEpochDay)
VALUES('visual-card-installment','cgb','手机 6 期免息',1962300,1308200,327050,0,4,CAST(strftime('%s','2026-05-01') / 86400 AS INTEGER));

-- 新机首次空库启动会留下当日 0 快照；若不纠正，净资产趋势与本月降债会把空库误当真实起点。
INSERT OR REPLACE INTO snapshots(id,dateEpochDay,totalAssets,totalDebts,netWorth)
VALUES('snapshot_20686',20686,470000,10000000,-9530000);

INSERT OR REPLACE INTO month_debt_anchors(yearMonth,totalDebtCents)
VALUES('2026-08',10000000);

COMMIT;
PRAGMA foreign_keys = ON;
PRAGMA quick_check;
