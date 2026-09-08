# lab-calendar-backend

Backend API server for Lab Calendar

## Design documents

- [ERD 및 DB 스키마 설계 (KAN-27, 팀 리뷰용 초안)](docs/database-design.md)

## Tech Stack

- Java 17
- Spring Boot 4.1 (Web, Data JPA, Validation, Lombok)
- Gradle
- MySQL

## Getting Started

1. Create a local MySQL database:
   ```sql
   CREATE DATABASE lab_calendar CHARACTER SET utf8mb4;
   ```
2. Copy `src/main/resources/application-local.yml.example` to
   `src/main/resources/application-local.yml` and fill in your DB credentials.
   This file is gitignored.
3. Run the app:
   ```bash
   ./gradlew bootRun
   ```

The server starts on http://localhost:8080. `GET /api/health` returns
`{"status":"ok"}` once it's up. CORS is configured to allow the frontend dev
server at http://localhost:5173.

## Profiles

The `local` profile is active by default (see `application.yml`) and loads
DB settings from `application-local.yml`.
