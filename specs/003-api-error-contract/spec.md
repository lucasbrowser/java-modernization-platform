# Especificação: Contrato de erros HTTP da API

## Estado

**Planejamento pronto para aprovação.** Esta especificação não autoriza implementação. As decisões funcionais abaixo foram aprovadas para planejamento pelo pedido de 2026-10-21; as tarefas de implementação ainda dependem de aprovação explícita.

## 1. Objetivo

Padronizar as respostas HTTP de erros da API nos cenários de JSON malformado/incompatível e de exceções inesperadas abrangidas pelo handler, preservando os contratos de erro existentes.

## 2. Contexto e fatos observados

- `ApiError` é um record com `timestamp`, `status`, `error`, `message` e `fields`.
- `GlobalExceptionHandler` já produz `ApiError` para recurso inexistente, argumento inválido, erro de validação de DTO, conflitos de optimistic locking, credenciais inválidas e e-mail já registrado.
- A validação de DTO produz HTTP 400, mensagem geral `Invalid request data` e um mapa `fields` que mantém a primeira mensagem por campo.
- Controllers de auth, customer, product e order recebem corpos JSON; os métodos aplicáveis anotam os corpos com `@Valid`.
- `GlobalExceptionHandlerTest` verifica diretamente alguns métodos do handler. Há testes de integração de controllers, mas a existência de respostas HTTP padronizadas para JSON malformado/incompatível e exceções inesperadas não foi comprovada pela inspeção realizada.
- Esta rodada não executou testes. O comportamento HTTP efetivo desses cenários permanece não verificado.

## 3. Decisões aprovadas

1. Reutilizar `ApiError` e `GlobalExceptionHandler`.
2. JSON malformado ou incompatível com o tipo esperado retorna HTTP 400 e corpo compatível com `ApiError`.
3. Exceções inesperadas abrangidas pelo handler retornam HTTP 500 com mensagem genérica, sem detalhes internos.
4. A resposta de erro inesperado não expõe stack trace, SQL, mensagem interna de exceção, nome de classe ou dado sensível.
5. Preservar status, campos e formatos existentes; preservar detalhes de validação por campo e comportamentos atuais de autenticação, autorização, recurso inexistente, argumento inválido, optimistic locking, credenciais inválidas e e-mail já registrado.
6. Não alterar respostas de sucesso, regras de negócio ou contratos de endpoints.
7. Não incluir logging, tracing, métricas, mudanças de autenticação/autorização ou tratamento amplo de falhas de persistência.
8. Testes devem cobrir os novos comportamentos, regressões dos mapeamentos existentes e as respostas HTTP efetivas.

## 4. Matriz de erros

| Cenário | Origem/exceção prevista | Status | Corpo esperado | Verificação de aceitação |
|---|---|---:|---|---|
| JSON malformado | Erro de leitura/desserialização do corpo HTTP (normalmente `HttpMessageNotReadableException`) | 400 | `ApiError`; `error` e `message` genéricos apropriados para requisição inválida; `fields` nulo salvo necessidade demonstrada | Teste HTTP envia JSON sintaticamente inválido e verifica status, estrutura e ausência de detalhes internos do parser |
| JSON incompatível com tipo esperado | Erro de leitura/desserialização do corpo HTTP (normalmente `HttpMessageNotReadableException`), como valor incompatível com tipo de campo | 400 | Mesmo contrato genérico de erro de requisição inválida em `ApiError` | Teste HTTP envia JSON válido com tipo incompatível e verifica status e estrutura |
| DTO inválido por Bean Validation | `MethodArgumentNotValidException` | 400 | Preservar `ApiError(400, "Validation Error", "Invalid request data", fields)` e timestamp; `fields` mantém as mensagens por campo | Regressão HTTP confirma status, texto geral e mapa de campos |
| Recurso inexistente | `ResourceNotFoundException` | 404 | Preservar `ApiError` atual; mensagem é a mensagem já fornecida pela exceção; `fields` nulo | Regressão do handler/HTTP confirma 404 e formato existente |
| Argumento inválido | `IllegalArgumentException` | 400 | Preservar `ApiError` atual, inclusive mensagem atual; `fields` nulo | Regressão confirma 400 e formato existente |
| Conflito de optimistic locking | `OptimisticLockingFailureException`, `OptimisticLockException` ou `StaleObjectStateException` | 409 | Preservar mensagem fixa `The resource was modified by another operation.`; `fields` nulo | Regressão confirma 409 e mensagem genérica atual |
| Credenciais inválidas | `InvalidCredentialsException` | 401 | Preservar `ApiError` atual e sua mensagem; `fields` nulo | Regressão confirma 401 e formato atual |
| E-mail já registrado | `EmailAlreadyRegisteredException` | 409 | Preservar `ApiError` atual e sua mensagem; `fields` nulo | Regressão confirma 409 e formato atual |
| Exceção inesperada coberta pelo handler | Exceção não tratada especificamente, conforme mecanismo final escolhido na implementação | 500 | `ApiError` com mensagem genérica fixa; `fields` nulo; nenhuma informação da exceção | Teste HTTP provoca falha controlada e verifica status, formato e ausência de texto sentinela, SQL, nomes de classes e stack trace |
| Autenticação/autorização | Spring Security e/ou filtros de segurança | Comportamento atual | Fora do novo mapeamento; preservar comportamento existente | Regressão de segurança não deve ser enfraquecida nem convertida inadvertidamente no handler genérico |

Os valores exatos de `error` e `message` para JSON inválido e a mensagem genérica para HTTP 500 serão definidos como constantes estáveis no planejamento de implementação antes da primeira tarefa de código. Devem ser neutros e não conter mensagens da exceção. O campo `timestamp` permanece gerado como hoje. A resposta usa apenas os campos já existentes em `ApiError`.

## 5. Critérios de aceitação

- **CA-01:** JSON malformado retorna 400 com corpo compatível com `ApiError`.
- **CA-02:** JSON válido com tipo incompatível retorna 400 com corpo compatível com `ApiError`.
- **CA-03:** exceções inesperadas abrangidas retornam 500 com mensagem genérica.
- **CA-04:** erro inesperado não revela stack trace, SQL, mensagem interna, nome de classe ou dado sensível.
- **CA-05:** os mapeamentos existentes preservam status, formato e mensagens definidos na matriz.
- **CA-06:** erro de validação continua expondo detalhes por campo no formato atual.
- **CA-07:** testes de integração exercitam as respostas HTTP efetivas para os novos comportamentos.
- **CA-08:** testes automatizados cobrem regressões relevantes dos erros existentes.
- **CA-09:** respostas de sucesso, regras de negócio e contratos de endpoints não mudam.
- **CA-10:** nenhuma alteração preventiva em `ApiError` é feita; se a implementação provar uma necessidade, a menor mudança será justificada e aprovada antes de alterar o contrato.

## 6. Fora do escopo

- Modificar respostas de sucesso, controllers funcionais, regras de negócio ou endpoints.
- Alterar autenticação/autorização ou a semântica de erros produzidos pelo Spring Security.
- Criar esquema alternativo de erro ou campos novos sem necessidade comprovada.
- Logging, tracing, métricas, migrations e mudanças de persistência.
- Padronizar amplamente todas as exceções possíveis do framework ou do banco.

## 7. Questões e limites conhecidos

- O tipo exato da exceção lançada em cada cenário de desserialização e a resposta atual no servidor HTTP ainda não foram confirmados por teste de integração.
- O alcance seguro do mapeamento genérico de HTTP 500 deve ser verificado para garantir que exceções de segurança não sejam capturadas ou convertidas indevidamente.
- A inspeção estática não mostrou necessidade de adaptar `ApiError`; não se deve adicionar campos preventivamente.
- Os testes HTTP devem usar uma falha controlada e determinística, sem depender de falha real de banco ou serviço externo.

## 8. Evidência da T-01 — 2026-10-21

Esta seção atualiza o estado preliminar registrado antes da execução da T-01:

- O novo `ApiErrorContractIntegrationTest` usa `@WebMvcTest` com `AuthController`, `GlobalExceptionHandler` e `SecurityConfig` reais no pipeline MVC; o `AuthService` e o `JwtService` são mocks, conforme o padrão de teste de controller existente.
- Os dois corpos inválidos foram resolvidos como `HttpMessageNotReadableException`, com status 400 e corpo vazio no MockMvc. Não houve serialização de `ApiError`.
- A falha `IllegalStateException` controlada escapou do MockMvc como `ServletException`; o cenário não chegou a produzir uma resposta HTTP para verificar vazamento ou mensagem 500.
- Tentativas com servidor aleatório retornaram 401 nos três caminhos de teste, incluindo chamadas com token gerado pelo `JwtService`. A causa não foi determinada; a configuração de produção permaneceu intacta.
- Mensagens propostas para a implementação, ainda não observadas como respostas HTTP: JSON ilegível — `Malformed request body`; erro inesperado — `An unexpected error occurred.` A estabilidade/ausência de vazamento da mensagem de 500 ainda requer teste de resposta HTTP na T-02.
- Os resultados dos testes direcionados e de `mvn clean test` constam em `tasks.md`.

Limitação: T-01 confirmou a origem MVC dos dois erros de desserialização e que o handler atual não os mapeia para `ApiError`. Não confirmou por HTTP real o corpo desses erros nem o comportamento da resposta inesperada; os testes MockMvc são a alternativa próxima usada nesta tarefa.

## 9. Estado após T-02

A T-02 implementou os mapeamentos descritos nas decisões: `HttpMessageNotReadableException` produz `ApiError` 400 com `Malformed request body`; exceção inesperada abrangida produz `ApiError` 500 com `An unexpected error occurred.`. O corpo usa os campos existentes e `fields=null`. Testes MockMvc verificam status, corpo e ausência de texto interno; a suíte de regressão de segurança passou. Evidências completas e limites do uso de MockMvc estão em `tasks.md`.

## 10. Estado final verificado após T-03 — 2026-10-21

As anotações preliminares das seções anteriores sobre estado ainda não implementado e testes pendentes foram supersedidas pelos resultados das T-01/T-02/T-03 documentados em `tasks.md`. O `GlobalExceptionHandler` implementa 400 `ApiError` para corpo JSON ilegível (`Malformed request body`) e 500 `ApiError` genérico (`An unexpected error occurred.`); os testes de contrato também verificam validação por campo e ausência de detalhes internos na resposta 500. `ApiError` preserva sua forma existente.

A regressão final relaciona os critérios a testes: JSON inválido, campos de validação e exceção inesperada em `ApiErrorContractIntegrationTest`; 404/400/409 do handler em `GlobalExceptionHandlerTest`; 401/409 de autenticação em `AuthControllerIntegrationTest`; e regressões dos controllers/security nas respectivas suítes. Os testes de respostas novas usam MockMvc com advice e segurança reais no pipeline MVC. Não se afirma que esses cenários foram verificados por socket HTTP; a tentativa com servidor aleatório da T-01 retornou 401 e continua como risco residual. Resultados e comandos exatos estão em `tasks.md`.
