CREATE TABLE `wt`(
  `col0` string,
  `col1` string,
  `col2` int,
  `col3` string,
  `col4` string,
  `col5` string,
  `col6` string,
  `col7` string,
  `col8` string,
  `col9` string,
  `col10` string,
  `col11` string,
  `col12` string,
  `col13` string,
  `col14` string);

CREATE TABLE `tbl1`(
  `col2` bigint,
  `col4` string,
  `col5` string);

CREATE TABLE `tbl2`(
  `col2` bigint,
  `col4` string,
  `col5` string);

CREATE TABLE `tbl3`(
  `col4` string,
  `col5` string,
  `col6` string);

CREATE TABLE `tbl4`(
  `col1` string,
  `col2` string,
  `col4` string,
  `col5` string);

CREATE TABLE `tbl5`(
  `col1` string,
  `col2` string,
  `col4` string,
  `col5` string);

CREATE TABLE `tbl6`(
  `col2` string,
  `col4` string,
  `col5` string);


explain
select count(*)
from wt wide_tbl
inner join (
  select 
  `a`.`col2`,
  `a`.`col4`,
  `a`.`col5`
  from `tbl1` `a`
  where `a`.`col4`='string1' and `a`.`col5`='string2'
  union all
  select
  `b`.`col2`,
  `b`.`col4`,
  `b`.`col5`
  from `tbl2` `b`
  where `b`.`col4`='string1' and `b`.`col5`='string2') union_tbl on
                union_tbl.col2=wide_tbl.col2
                and union_tbl.col4=wide_tbl.col4
                and union_tbl.col5=wide_tbl.col5
left join tbl5 tbl5_0 on
                tbl5_0.col2=wide_tbl.col0
                and tbl5_0.col1='string0'
                and tbl5_0.col4=wide_tbl.col4
                and tbl5_0.col5=wide_tbl.col5
left join tbl6 tbl6_0 on
                tbl6_0.col2=wide_tbl.col0
                and tbl6_0.col4=wide_tbl.col4
                and tbl6_0.col5=wide_tbl.col5
left join tbl3 tbl3_0 on
                tbl3_0.col6=wide_tbl.col6
                and tbl3_0.col4=wide_tbl.col4
                and tbl3_0.col5=wide_tbl.col5
left join tbl4 tbl4_0 on
                tbl4_0.col2=wide_tbl.col1
                and tbl4_0.col1='string0'
                and tbl4_0.col4=wide_tbl.col4
                and tbl4_0.col5=wide_tbl.col5
left join tbl5 tbl5_1 on
                tbl5_1.col2=wide_tbl.col7
                and tbl5_1.col1='string0'
                and tbl5_1.col4=wide_tbl.col4
                and tbl5_1.col5=wide_tbl.col5
left join tbl5 tbl5_2 on
                tbl5_2.col2=wide_tbl.col8
                and tbl5_2.col1='string0'
                and tbl5_2.col4=wide_tbl.col4
                and tbl5_2.col5=wide_tbl.col5
left join tbl5 tbl5_3 on
                tbl5_3.col2=wide_tbl.col9
                and tbl5_3.col1='string0'
                and tbl5_3.col4=wide_tbl.col4
                and tbl5_3.col5=wide_tbl.col5
left join tbl5 tbl5_4 on
                tbl5_4.col2=wide_tbl.col10
                and tbl5_4.col1='string0'
                and tbl5_4.col4=wide_tbl.col4
                and tbl5_4.col5=wide_tbl.col5
left join tbl5 tbl5_5 on
                tbl5_5.col2=wide_tbl.col11
                and tbl5_5.col1='string0'
                and tbl5_5.col4=wide_tbl.col4
                and tbl5_5.col5=wide_tbl.col5
left join tbl5 tbl5_6 on
                tbl5_6.col2=wide_tbl.col12
                and tbl5_6.col1='string0'
                and tbl5_6.col4=wide_tbl.col4
                and tbl5_6.col5=wide_tbl.col5
left join tbl5 tbl5_7 on
                tbl5_7.col2=wide_tbl.col13
                and tbl5_7.col1='string0'
                and tbl5_7.col4=wide_tbl.col4
                and tbl5_7.col5=wide_tbl.col5
left join tbl5 tbl5_8 on
                tbl5_8.col2=wide_tbl.col14
                and tbl5_8.col1='string0'
                and tbl5_8.col4=wide_tbl.col4
                and tbl5_8.col5=wide_tbl.col5
left join tbl5 tbl5_9 on
                tbl5_9.col2=wide_tbl.col3
                and tbl5_9.col1='string0'
                and tbl5_9.col4=wide_tbl.col4
                and tbl5_9.col5=wide_tbl.col5
where wide_tbl.col4='string1' and wide_tbl.col5='string1';
