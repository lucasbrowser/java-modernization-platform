# Especificação: Observabilidade da API

## 1. Objetivo

Disponibilizar informações de saúde e métricas da aplicação para facilitar o diagnóstico de problemas operacionais.

## 2. Escopo

- Disponibilizar health checks da aplicação.
- Expor indicadores de liveness e readiness.
- Disponibilizar métricas compatíveis com Prometheus.
- Preservar os mecanismos de segurança existentes.

## 3. Requisitos funcionais

### RF-01 — Health check
A aplicação deve informar seu estado de saúde por meio do endpoint configurado.

### RF-02 — Liveness
Deve ser possível verificar se a aplicação está em execução.

### RF-03 — Readiness
Deve ser possível verificar se a aplicação está apta a receber requisições, respeitando a configuração adotada para dependências.

### RF-04 — Métricas
A aplicação deve expor métricas no formato esperado pelo Prometheus.

### RF-05 — Segurança
Os endpoints públicos devem se limitar aos explicitamente autorizados. O endpoint de métricas deve preservar a proteção prevista na configuração de segurança.

## 4. Requisitos não funcionais

- Compatibilidade com Java 21 e a versão do Spring Boot definida pelo projeto.
- Não introduzir dependências desnecessárias.
- Manter a arquitetura modular existente.
- Não expor informações sensíveis nos endpoints públicos.

## 5. Critérios de aceitação

- CA-01: health check retorna o estado esperado quando a aplicação está saudável.
- CA-02: liveness e readiness apresentam os estados esperados.
- CA-03: as métricas podem ser obtidas por um cliente Prometheus autorizado.
- CA-04: requisições não autorizadas ao endpoint protegido são rejeitadas.
- CA-05: os testes automatizados existentes continuam passando.
- CA-06: a documentação descreve os endpoints e os requisitos de acesso.

## 6. Fora do escopo

- Implantação em produção.
- Configuração de infraestrutura externa não existente no repositório.
- Alterações em regras de negócio dos módulos customer, product e order.

## 7. Dúvidas e decisões pendentes

Antes de implementar, verificar a configuração atual do Actuator, do Spring Security e do Prometheus. Não duplicar funcionalidades já existentes.