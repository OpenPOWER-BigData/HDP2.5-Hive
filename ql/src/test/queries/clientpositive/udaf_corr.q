DROP TABLE covar_tab;
CREATE TABLE covar_tab (a INT, b INT, c INT)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
STORED AS TEXTFILE;
LOAD DATA LOCAL INPATH '../../data/files/covar_tab.txt' OVERWRITE
INTO TABLE covar_tab;

DESCRIBE FUNCTION corr;
DESCRIBE FUNCTION EXTENDED corr;
SELECT round(corr(b, c), 6) FROM covar_tab WHERE a < 1;
SELECT round(corr(b, c), 6) FROM covar_tab WHERE a < 3;
SELECT round(corr(b, c), 6) FROM covar_tab WHERE a = 3;
SELECT a, round(corr(b, c), 6) FROM covar_tab GROUP BY a ORDER BY a;
SELECT round(corr(b, c), 6) FROM covar_tab;

DROP TABLE covar_tab;
