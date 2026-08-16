# Jobvis API

Jobvis의 지원 현황, 일정, 메일 연결과 통계 기능을 제공할 Spring Boot API입니다.
현재 저장소는 웹 계층과 입력 검증, 상태 확인을 위한 초기 실행 기반만 포함합니다.
인증, 데이터베이스, 채용 메일 및 캘린더 연동은 아직 구현하지 않았습니다.

## 기술 구성

- Java 17
- Spring Boot 4.1.0
- Gradle Kotlin DSL
- Spring Web MVC, Validation, Actuator

## 로컬 실행

```bash
./gradlew bootRun
```

애플리케이션이 실행되면 상태 확인 엔드포인트를 사용할 수 있습니다.

```text
GET http://localhost:8080/actuator/health
```

## 검증

```bash
./gradlew test
./gradlew bootJar
```
