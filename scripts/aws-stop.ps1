# 개발/데모가 끝났을 때 실행 — EC2 + RDS를 중지해서 컴퓨팅 과금을 멈춘다.
# Elastic IP는 그대로 유지되므로 DNS(lab-calendar.cloud)는 안 바뀐다.
# 참고: RDS는 7일 넘게 중지 상태로 두면 AWS가 자동으로 다시 시작시킨다.
$ErrorActionPreference = "Stop"
$env:PATH += ";C:\Program Files\Amazon\AWSCLIV2"

$InstanceId = "i-04125fcb4c7d2e6f8"
$DbInstanceId = "lab-calendar-db"

Write-Output "Stopping EC2 ($InstanceId) ..."
aws ec2 stop-instances --instance-ids $InstanceId | Out-Null

Write-Output "Stopping RDS ($DbInstanceId) ..."
aws rds stop-db-instance --db-instance-identifier $DbInstanceId | Out-Null

Write-Output "Requested. They take a minute or two to actually stop — check with:"
Write-Output "  aws ec2 describe-instances --instance-ids $InstanceId --query 'Reservations[0].Instances[0].State.Name'"
Write-Output "  aws rds describe-db-instances --db-instance-identifier $DbInstanceId --query 'DBInstances[0].DBInstanceStatus'"
