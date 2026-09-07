# 개발/데모를 시작할 때 실행 — EC2 + RDS를 다시 켠다.
# 완전히 뜨는 데 보통 5~10분 정도 걸린다 (RDS가 가장 오래 걸림).
# backend 컨테이너는 docker의 restart:unless-stopped 정책 덕분에
# EC2가 부팅되면 자동으로 다시 뜨고, RDS가 준비될 때까지 알아서 재시도한다.
$ErrorActionPreference = "Stop"
$env:PATH += ";C:\Program Files\Amazon\AWSCLIV2"

$InstanceId = "i-04125fcb4c7d2e6f8"
$DbInstanceId = "lab-calendar-db"

Write-Output "Starting RDS ($DbInstanceId) ..."
aws rds start-db-instance --db-instance-identifier $DbInstanceId | Out-Null

Write-Output "Starting EC2 ($InstanceId) ..."
aws ec2 start-instances --instance-ids $InstanceId | Out-Null

Write-Output "Requested. Waiting for RDS to become available (this is the slow part)..."
aws rds wait db-instance-available --db-instance-identifier $DbInstanceId
Write-Output "RDS is up. https://lab-calendar.cloud should be reachable within a minute or two."
