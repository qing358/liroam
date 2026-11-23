-- 会员等级卡背景图（li_member_grade.grade_background）仍为上游图床出厂图时换成本站素材（buyer、manager 的 public/liroam/member/）；
-- 运营换过的不动。库里没有该表或该列（自有迁移的空库/测试库）时跳过。

SET @upstream_oss := 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/2117947015370043393/';
SET @liroam_has_card := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'li_member_grade' AND column_name = 'grade_background');
SET @liroam_card_sql := IF(@liroam_has_card > 0,
    'UPDATE li_member_grade SET grade_background = ? WHERE id = ? AND grade_background = ?',
    'DO ?, ?, ?');
PREPARE liroam_card FROM @liroam_card_sql;
SET @id := 1, @new := '/liroam/member/grade-card-1.svg', @old := CONCAT(@upstream_oss, 'd6d4dcda15de41a7a3d32d0285395c04.jpg');
EXECUTE liroam_card USING @new, @id, @old;
SET @id := 2, @new := '/liroam/member/grade-card-2.svg', @old := CONCAT(@upstream_oss, '9ac25f921c6e44a58473f3b85bede739.jpg');
EXECUTE liroam_card USING @new, @id, @old;
SET @id := 3, @new := '/liroam/member/grade-card-3.svg', @old := CONCAT(@upstream_oss, 'fc96739b017e4f2fbef26b1358778362.jpg');
EXECUTE liroam_card USING @new, @id, @old;
SET @id := 4, @new := '/liroam/member/grade-card-4.svg', @old := CONCAT(@upstream_oss, 'a6b486be1852414c95bca905e251b1ad.jpg');
EXECUTE liroam_card USING @new, @id, @old;
DEALLOCATE PREPARE liroam_card;
