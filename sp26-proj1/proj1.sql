-- Before running drop any existing views
DROP VIEW IF EXISTS q0;
DROP VIEW IF EXISTS q1i;
DROP VIEW IF EXISTS q1ii;
DROP VIEW IF EXISTS q1iii;
DROP VIEW IF EXISTS q1iv;
DROP VIEW IF EXISTS q2i;
DROP VIEW IF EXISTS q2ii;
DROP VIEW IF EXISTS q2iii;
DROP VIEW IF EXISTS q3i;
DROP VIEW IF EXISTS q3ii;
DROP VIEW IF EXISTS q3iii;
DROP VIEW IF EXISTS q4i;
DROP VIEW IF EXISTS q4ii;
DROP VIEW IF EXISTS q4iii;
DROP VIEW IF EXISTS q4iv;
DROP VIEW IF EXISTS q4v;

-- Question 0
CREATE VIEW q0(era)
AS
SELECT max(era)
from pitching
;

-- Question 1i
CREATE VIEW q1i(namefirst, namelast, birthyear)
AS
select people.nameFirst, people.nameLast, people.birthYear
from people
where weight > 300
;

-- Question 1ii
CREATE VIEW q1ii(namefirst, namelast, birthyear)
AS
select people.nameFirst, people.nameLast, people.birthYear
from people
where nameFirst like '% %'
order by nameFirst, nameLast
;

-- Question 1iii
CREATE VIEW q1iii(birthyear, avgheight, count)
AS
select people.birthYear, avg(people.height), count(*)
from people
group by birthYear
order by birthYear
;

-- Question 1iv
CREATE VIEW q1iv(birthyear, avgheight, count)
AS
select people.birthYear, avg(people.height), count(*)
from people
group by birthYear
having avg(height) > 70
order by birthYear
;

-- Question 2i
CREATE VIEW q2i(namefirst, namelast, playerid, yearid)
AS
select p.nameFirst, p.nameLast, p.playerID, h.yearid
from people as p inner join halloffame as h
on p.playerID = h.playerID
where h.inducted = 'Y'
order by h.yearid DESC, p.playerID
;

-- Question 2ii
CREATE VIEW q2ii(namefirst, namelast, playerid, schoolid, yearid)
AS
select q.namefirst, q.namelast, q.playerid, cs.schoolid, q.yearid
from q2i as q inner join
(
    select c.playerid as playerid, s.schoolID as schoolid
    from collegeplaying as c inner join schools as s
    on c.schoolID = s.schoolID
    where s.schoolState = 'CA'
) as cs
on q.playerid = cs.playerid
order by q.yearid desc, cs.schoolid, q.playerid
;

-- Question 2iii
CREATE VIEW q2iii(playerid, namefirst, namelast, schoolid)
AS
select q.playerid, q.namefirst, q.namelast, cs.schoolID
from q2i as q left join
(
    select c.schoolID, c.playerid as playerid
    from collegeplaying as c inner join schools as s
    on c.schoolID = s.schoolID
) as cs
on q.playerid = cs.playerid
order by q.playerid desc, cs.schoolID
;

-- Question 3i
CREATE VIEW q3i(playerid, namefirst, namelast, yearid, slg)
AS
select b.playerID, p.nameFirst, p.nameLast, yearID,
       (1.0 * ((H - H2B - H3B - HR) + 2 * H2B + 3 * H3B + 4 * HR) / AB) as slg
from batting as b inner join people as p
on b.playerID = p.playerID
where AB > 50
order by slg desc, yearID, b.playerID
limit 10
;

-- Question 3ii
CREATE VIEW q3ii(playerid, namefirst, namelast, lslg)
AS
select b.playerID, nameFirst, nameLast,
       (1.0 * ((sum(H) - sum(H2B) - sum(H3B) - sum(HR)) + 2 * sum(H2B) + 3 * sum(H3B) + 4 * sum(HR)) / sum(AB)) as lslg
from batting as b inner join people as p on b.playerID = p.playerID
group by p.playerID, nameFirst, nameLast
having sum(AB) > 50
order by lslg desc, p.playerID
limit 10
;

-- Question 3iii
CREATE VIEW q3iii(namefirst, namelast, lslg)
AS
select  nameFirst, nameLast,
       (1.0 * ((sum(H) - sum(H2B) - sum(H3B) - sum(HR)) + 2 * sum(H2B) + 3 * sum(H3B) + 4 * sum(HR)) / sum(AB)) as lslg
from batting as b inner join people as p on b.playerID = p.playerID
group by p.playerID, nameFirst, nameLast
having sum(AB) > 50
and (1.0 * ((sum(H) - sum(H2B) - sum(H3B) - sum(HR)) + 2 * sum(H2B) + 3 * sum(H3B) + 4 * sum(HR)) / sum(AB)) > (
    select (1.0 * ((sum(H) - sum(H2B) - sum(H3B) - sum(HR)) + 2 * sum(H2B) + 3 * sum(H3B) + 4 * sum(HR)) / sum(AB))
    from batting as b
    where b.playerID = 'mayswi01'
    )
;

-- Question 4i
CREATE VIEW q4i(yearid, min, max, avg)
AS
select yearID, min(salary), max(salary), avg(salary)
from salaries
group by yearID
order by yearID
;

-- Question 4ii difficult!!
-- Solve the problem based on the select part: low and high only
-- involves two tables, so solve them first, then think of the count which involves three tables
CREATE VIEW q4ii(binid, low, high, count)
AS
WITH stats AS (
    SELECT min(salary) AS min_sal,
           (max(salary) - min(salary)) / 10.0 AS unit
    FROM salaries
    WHERE yearID = 2016
)
SELECT b.binid,
       stats.min_sal + b.binid * stats.unit AS low,
       stats.min_sal + (b.binid + 1) * stats.unit AS high,
       COUNT(s.salary) AS count
FROM binids AS b
CROSS JOIN stats
LEFT JOIN salaries AS s ON s.yearID = 2016
    AND s.salary >= stats.min_sal + b.binid * stats.unit
    AND (s.salary < stats.min_sal + (b.binid + 1) * stats.unit OR b.binid = 9)
GROUP BY b.binid
ORDER BY b.binid
;

-- Question 4iii
CREATE VIEW q4iii(yearid, mindiff, maxdiff, avgdiff)
AS
select r1.yearid, r1.min - r2.min, r1.max - r2.max, r1.avg - r2.avg
from q4i as r1 inner join q4i as r2 on r1.yearid = r2.yearid + 1
order by r1.yearid
;

-- Question 4iv
CREATE VIEW q4iv(playerid, namefirst, namelast, salary, yearid)
AS
select p.playerID, nameFirst, nameLast, salary, s.yearid
from salaries as s inner join q4i as r on s.yearID = r.yearid
inner join people as p on s.playerID = p.playerID
where salary = max
and s.yearID in (2000, 2001)
;
-- Question 4v
CREATE VIEW q4v(team, diffAvg)
AS
select a.teamID, max(salary) - min(salary)
from allstarfull as a inner join salaries as s on a.playerID = s.playerID
inner join teams as t on a.team_ID = t.ID and s.team_ID = t.ID
and a.yearID = t.yearID and s.yearID = t.yearID
and t.yearID = 2016
group by a.teamID
;

