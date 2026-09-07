# scripts/

로컬(본인 PC, `aws configure` 완료된 환경)에서 실행하는 운영 스크립트.
EC2에 올라가는 파일이 아니라 순수하게 AWS 리소스를 켜고 끄기 위한 것.

- `aws-stop.ps1` — 개발/데모 끝나면 실행. EC2 + RDS 컴퓨팅 비용을 멈춘다.
- `aws-start.ps1` — 다시 시작할 때 실행. RDS가 준비될 때까지 기다려준다.

Elastic IP는 stop/start와 무관하게 고정이라 `lab-calendar.cloud` DNS는 안 바뀐다.
단, 퍼블릭 IP 자체에 붙는 월 소액 과금(AWS 2024년 정책)은 인스턴스를 꺼도 계속 발생한다.

RDS를 7일 넘게 중지 상태로 방치하면 AWS가 자동으로 다시 시작시킨다 (RDS 자체 정책).
