# Java Modernization Platform

> Plataforma corporativa desenvolvida com Java e Spring Boot, criada para demonstrar na prática a modernização progressiva de um sistema empresarial, desde uma arquitetura modular até uma solução distribuída, orientada a eventos e preparada para Cloud.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)
![Docker](https://img.shields.io/badge/Docker-Ready-blue)
![JWT](https://img.shields.io/badge/Security-JWT-red)
![Tests](https://img.shields.io/badge/Tests-JUnit%205-green)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

## Sobre o projeto

O **Java Modernization Platform** é um projeto de portfólio desenvolvido com foco em práticas utilizadas no desenvolvimento de aplicações corporativas modernas.

O projeto representa uma plataforma de gerenciamento de clientes, produtos e pedidos e será evoluído progressivamente, simulando um cenário real de **modernização de um sistema corporativo legado**.

A primeira versão utiliza uma arquitetura modular baseada em Spring Boot. Nas próximas etapas, a aplicação será evoluída para uma arquitetura distribuída utilizando microsserviços, mensageria, arquitetura orientada a eventos, containers, Kubernetes, CI/CD e AWS.

A proposta é demonstrar não apenas conhecimento de tecnologias, mas também **decisões arquiteturais, qualidade de código, testes, segurança, integração e práticas de engenharia de software**.

---

## Objetivos

Este projeto tem como principais objetivos demonstrar experiência prática com:

* Java moderno
* Spring Boot
* Desenvolvimento de APIs REST
* Arquitetura de aplicações corporativas
* PostgreSQL
* JPA / Hibernate
* Segurança com JWT
* Validação de APIs
* Tratamento de exceções
* Testes automatizados
* Docker
* CI/CD
* Arquitetura orientada a eventos
* Mensageria
* Microsserviços
* Kubernetes
* AWS
* Observabilidade
* Modernização de sistemas legados

---

## Arquitetura evolutiva

O projeto será desenvolvido de forma incremental.

### Fase 1 — Monólito modular

```text
                    ┌─────────────────────┐
                    │     REST API        │
                    └──────────┬──────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
        ┌─────▼─────┐    ┌────▼────┐     ┌─────▼─────┐
        │ Customers │    │ Products │     │  Orders   │
        └─────┬─────┘    └────┬────┘     └─────┬─────┘
              │               │                │
              └───────────────┼────────────────┘
                              │
                       ┌──────▼──────┐
                       │ PostgreSQL  │
                       └─────────────┘
```

### Fase 2 — Microsserviços

Os módulos serão gradualmente separados em serviços independentes:

```text
                         API Gateway
                              │
             ┌────────────────┼────────────────┐
             │                │                │
        Customer MS       Product MS        Order MS
             │                │                │
        PostgreSQL       PostgreSQL       PostgreSQL
```

### Fase 3 — Arquitetura orientada a eventos

```text
                       Order Service
                             │
                             │ OrderCreated
                             ▼
                      ┌─────────────┐
                      │  RabbitMQ   │
                      └──────┬──────┘
                             │
                ┌────────────┼────────────┐
                │            │            │
                ▼            ▼            ▼
           Inventory      Payment     Notification
             Service       Service       Service
```

### Fase 4 — Cloud / Kubernetes

A aplicação será posteriormente preparada para execução em ambiente AWS utilizando containers, Kubernetes e serviços gerenciados.

---

## Funcionalidades atuais

### Primeiro administrador

O startup da API não cria uma conta administrativa. Depois que o PostgreSQL e as migrations estiverem prontos, execute o bootstrap explicitamente em um terminal interativo:

```powershell
docker compose --profile bootstrap run --rm bootstrap
```

O comando solicita nome e e-mail e lê a senha sem eco. Configure `BOOTSTRAP_DB_PASSWORD` no ambiente protegido usado pelo Compose; essa credencial é consumida somente pelo comando de bootstrap. O processo API recebe apenas as credenciais `app_runtime`. Códigos de saída: `0` criado ou já existente, `2` entrada/configuração inválida, `3` conflito de conta e `1` falha operacional.

### Autenticação

* Login utilizando JWT
* Spring Security
* Controle de acesso aos endpoints
* Senhas armazenadas utilizando hash seguro

### Clientes

* Cadastro
* Consulta
* Atualização
* Exclusão
* Validação dos dados

### Produtos

* Cadastro
* Consulta
* Atualização
* Exclusão
* Controle de estoque
* Validação dos dados

### Pedidos

* Criação de pedidos
* Associação com cliente
* Associação com produtos
* Cálculo do valor total
* Atualização do estoque
* Controle transacional
* Consulta de pedidos

---

## Tecnologias

### Backend

* Java 21
* Spring Boot
* Spring Web
* Spring Data JPA
* Hibernate
* Spring Security
* Bean Validation
* Spring Actuator

### Banco de dados

* PostgreSQL
* Flyway

### Segurança

* JWT
* Spring Security
* BCrypt

### Documentação

* OpenAPI
* Swagger UI

### Testes

* JUnit 5
* Mockito
* Testes unitários
* Testes de integração
* Testcontainers — evolução planejada

### DevOps

* Docker
* Docker Compose
* GitHub Actions

### Evolução planejada

* RabbitMQ / Kafka
* Microsserviços
* Kubernetes
* AWS
* CI/CD avançado
* Observabilidade
* OpenTelemetry

---

## Estrutura do projeto

```text
java-modernization-platform/
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── br/com/modernization/platform/
│   │   │       ├── auth/
│   │   │       ├── customer/
│   │   │       ├── product/
│   │   │       ├── order/
│   │   │       └── common/
│   │   │
│   │   └── resources/
│   │       └── db/
│   │           └── migration/
│   │
│   └── test/
│
├── .github/
│   └── workflows/
│       └── ci.yml
│
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

## Requisitos

Para executar o projeto localmente:

* Java 21
* Maven 3.9+
* Docker
* Docker Compose

---

## Executando o projeto

Clone o repositório:

```bash
git clone https://github.com/lucasbrowser/java-modernization-platform.git
```

Entre no diretório:

```bash
cd java-modernization-platform
```

Suba o PostgreSQL:

```bash
docker compose up -d postgres
```

Execute a aplicação:

```bash
mvn spring-boot:run
```

Ou execute toda a aplicação utilizando Docker:

```bash
docker compose up --build
```

---

## API

A aplicação estará disponível em:

```text
http://localhost:8080/api
```

Swagger UI:

```text
http://localhost:8080/api/swagger-ui.html
```

### Observabilidade

A API principal e o servidor de gerenciamento usam portas e caminhos diferentes. A API atende em `http://localhost:8080/api` por padrão (`SERVER_PORT` pode alterar a porta). O Actuator usa a porta de gerenciamento `8081` e o base path padrão `/actuator`; esse servidor separado não herda o contexto `/api` da API.

Ao executar com `mvn spring-boot:run`, componha os caminhos da tabela abaixo com `http://localhost:8081` para obter as URLs completas dos endpoints de gerenciamento. No Docker Compose, a API publica apenas a porta `8080` no host. A porta de gerenciamento `8081` não é publicada e fica acessível ao Prometheus pela rede interna, no endereço `api:8081` (`MANAGEMENT_SERVER_PORT` é definido pelo Compose).

| Endpoint | Finalidade | Autenticação | Resultado esperado |
| --- | --- | --- | --- |
| `/actuator/health` | Estado geral da aplicação | Pública | HTTP 200 e `UP` quando saudável |
| `/actuator/health/liveness` | Indica se a aplicação está em execução | Pública | HTTP 200 e `UP` quando saudável |
| `/actuator/health/readiness` | Indica se a aplicação está pronta para receber requisições | Pública | HTTP 200 e `UP` quando pronta |
| `/actuator/prometheus` | Métricas para scrape do Prometheus | HTTP Basic | HTTP 401 sem credenciais válidas; HTTP 200 com métricas para cliente autorizado |
| `/actuator/metrics` | Consulta de métricas do Actuator | JWT (Bearer) | Requer autenticação; acesso anônimo retorna HTTP 401 |
| `/actuator/info` | Informações da aplicação | JWT (Bearer) | Requer autenticação; acesso anônimo retorna HTTP 401 |

Os detalhes internos dos endpoints de health não são exibidos (`show-details: never`). O estado HTTP 200 e `UP` corresponde à aplicação saudável; a autorização do endpoint é uma verificação separada do estado de saúde.

O readiness mantém os grupos padrão do Actuator; o PostgreSQL não foi incluído explicitamente nesse grupo.

O scraper Prometheus recebe o usuário e a senha por configuração externa (`PROMETHEUS_USERNAME` e `PROMETHEUS_PASSWORD`). Configure essas variáveis no ambiente local apropriado; não registre valores de credenciais no README, em comandos compartilhados ou no repositório. No Compose, o endpoint é consumido internamente em `api:8081/actuator/prometheus`.

---

## Autenticação

A API utiliza autenticação baseada em JWT.

### Login

```http
POST /api/auth/login
Content-Type: application/json
```

Exemplo:

```json
{
  "email": "admin@platform.local",
  "password": "Admin@123"
}
```

Após o login, utilize o token retornado nas requisições protegidas:

```http
Authorization: Bearer <token>
```

> As credenciais apresentadas acima são destinadas apenas ao ambiente de desenvolvimento.

---

## Exemplo de criação de pedido

```http
POST /api/orders
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "customerId": 1,
  "items": [
    {
      "productId": 1,
      "quantity": 2
    }
  ]
}
```

O serviço valida o cliente, verifica a disponibilidade dos produtos, calcula o valor total e atualiza o estoque dentro de uma transação.

---

## Testes

Executar os testes:

```bash
mvn test
```

Executar o build:

```bash
mvn clean verify
```

A estratégia de testes será ampliada durante a evolução do projeto para incluir:

* testes unitários
* testes de integração
* testes de repository
* testes de API
* Testcontainers
* testes de contrato entre microsserviços

---

## CI/CD

O projeto possui uma pipeline inicial utilizando GitHub Actions.

A cada alteração enviada ao repositório, o pipeline deverá executar:

```text
Push
 │
 ▼
Checkout
 │
 ▼
Setup Java
 │
 ▼
Maven Build
 │
 ▼
Automated Tests
 │
 ▼
Verification
```

Nas próximas fases, o pipeline será ampliado para:

```text
Build
  ↓
Tests
  ↓
Code Quality
  ↓
Security Scan
  ↓
Docker Image
  ↓
Container Registry
  ↓
Deploy
```

---

## Roadmap

### Fase 1 — Backend fundamental

* [x] Java 21
* [x] Spring Boot
* [x] REST API
* [x] PostgreSQL
* [x] JPA / Hibernate
* [x] Flyway
* [x] JWT
* [x] Spring Security
* [x] Validation
* [x] Exception Handling
* [x] Swagger
* [x] Docker
* [x] Docker Compose
* [x] Testes unitários
* [x] GitHub Actions

### Fase 2 — Qualidade e arquitetura

* [x] Testcontainers
* [x] Testes de integração
* [x] Paginação
* [ ] Cache
* [ ] Idempotência
* [ ] Concorrência no estoque
* [ ] Melhorias de observabilidade
* [ ] Logs estruturados
* [ ] Métricas

### Fase 3 — Microsserviços

* [ ] API Gateway
* [ ] Customer Service
* [ ] Product Service
* [ ] Order Service
* [ ] Inventory Service
* [ ] Notification Service
* [ ] Configuração distribuída
* [ ] Service Discovery

### Fase 4 — Arquitetura orientada a eventos

* [ ] RabbitMQ / Kafka
* [ ] Eventos de domínio
* [ ] Comunicação assíncrona
* [ ] Retry
* [ ] Dead Letter Queue
* [ ] Idempotência de consumidores
* [ ] Eventual Consistency

### Fase 5 — Containers e Kubernetes

* [ ] Docker Images
* [ ] Kubernetes Deployments
* [ ] Services
* [ ] ConfigMaps
* [ ] Secrets
* [ ] Health Checks
* [ ] Horizontal Pod Autoscaler

### Fase 6 — AWS

* [ ] AWS RDS
* [ ] ECS / EKS
* [ ] SQS / SNS
* [ ] CloudWatch
* [ ] IAM
* [ ] Container Registry
* [ ] Infrastructure as Code

### Fase 7 — CI/CD

* [ ] Automated Build
* [ ] Automated Tests
* [ ] Security Scan
* [ ] Docker Build
* [ ] Container Registry
* [ ] Staging Environment
* [ ] Production Deployment

---

## Decisões arquiteturais

O projeto foi iniciado como um **monólito modular** propositalmente.

Em um cenário real de modernização, separar imediatamente uma aplicação em dezenas de microsserviços pode aumentar significativamente a complexidade operacional.

A abordagem adotada é:

```text
Monólito modular
       ↓
Limites de domínio bem definidos
       ↓
Testes e contratos
       ↓
Extração de serviços
       ↓
Comunicação assíncrona
       ↓
Cloud Native
```

Essa estratégia permite demonstrar uma abordagem incremental de modernização, reduzindo riscos e permitindo que cada etapa seja validada antes da próxima.

---

## Objetivo profissional

Este projeto foi desenvolvido como laboratório prático de engenharia de software e arquitetura de sistemas distribuídos, com foco nas tecnologias e práticas utilizadas em aplicações corporativas modernas.

A evolução do projeto busca demonstrar experiência com:

**Java + Spring Boot + APIs REST + PostgreSQL + Segurança + Testes + Docker + Microsserviços + Mensageria + Kubernetes + AWS + CI/CD.**

---

## Licença

Este projeto está disponível sob a licença MIT.
