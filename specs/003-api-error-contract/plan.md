# Plano técnico: Contrato de erros HTTP da API

## Estado

**Planejamento pronto para aprovação; implementação não iniciada.** Este documento não autoriza mudanças de código. A primeira tarefa de implementação requer aprovação explícita conforme `specs/README.md`.

## 1. Evidência e estado atual

- `ApiError` já oferece os campos necessários: timestamp, status, erro, mensagem e mapa opcional de campos.
- `GlobalExceptionHandler` cobre `ResourceNotFoundException`, `IllegalArgumentException`, `MethodArgumentNotValidException`, três exceções de optimistic locking, `InvalidCredentialsException` e `EmailAlreadyRegisteredException`.
- O handler atualmente usa a mensagem original para alguns erros de domínio/argumento e uma mensagem fixa para validação e optimistic locking. Esses comportamentos devem ser preservados.
- `GlobalExceptionHandlerTest` chama métodos do handler diretamente para alguns erros; isso não prova o comportamento do pipeline HTTP.
- Os controllers anotam corpos com `@RequestBody`; endpoints com validação usam `@Valid`. JSON malformado, tipo incompatível e falha inesperada ainda precisam de teste HTTP para confirmar a resposta efetiva.
- Nenhuma execução de testes foi feita nesta rodada de planejamento.

## 2. Abordagem técnica proposta

1. Reutilizar `ApiError` e adicionar ao `GlobalExceptionHandler` somente os mapeamentos necessários para erro de leitura de mensagem JSON e exceção inesperada.
2. Para JSON, retornar 400 com texto genérico estável, sem concatenar a mensagem interna do parser. Verificar cuidadosamente o tratamento de exceções de leitura encapsuladas.
3. Para exceções inesperadas dentro do escopo, retornar 500 e mensagem genérica fixa, sem serializar nem interpolar a exceção. Confirmar que o tratamento escolhido não captura falhas de segurança fora do aconselhamento MVC nem altera status de autenticação/autorização.
4. Manter `fields` nulo nos novos erros, salvo exigência concreta identificada durante a implementação; erros Bean Validation continuam usando o mapa existente.
5. Usar testes de integração HTTP para exercitar JSON malformado, JSON incompatível e uma falha inesperada controlada. Complementar os testes existentes do handler e dos endpoints para verificar regressões.
6. Não alterar API de sucesso, configuração, autenticação, autorização, persistência, README ou contratos por fora do escopo. Nenhuma mudança de `ApiError` está indicada pelo estado inspecionado.

## 3. Arquivos prováveis

- Produção: `src/main/java/com/github/lucasoliveira/platform/common/exception/GlobalExceptionHandler.java`.
- Teste unitário existente: `src/test/java/com/github/lucasoliveira/platform/common/exception/GlobalExceptionHandlerTest.java`.
- Testes de integração HTTP existentes ou novos sob `src/test/java/com/github/lucasoliveira/platform/`; definir localização conforme as convenções encontradas ao iniciar T-01.
- `ApiError.java` somente se uma incompatibilidade concreta com os critérios for demonstrada; atualmente não há alteração planejada.

## 4. Testes e verificação previstos

- Teste de integração com corpo JSON sintaticamente inválido: HTTP 400, estrutura `ApiError`, valores estáveis e sem detalhes do parser.
- Teste de integração com JSON válido contendo tipo incompatível com o DTO: HTTP 400 e mesma estrutura genérica.
- Teste de integração de falha inesperada controlada: HTTP 500, mensagem genérica e ausência de uma mensagem sentinela, SQL, nome da exceção ou stack trace no corpo.
- Testes de regressão para todos os mapeamentos atuais da matriz, preferencialmente verificando respostas HTTP nos fluxos pertinentes e preservando os testes unitários existentes.
- Teste de validação de DTO confirma `fields` por campo e mensagem geral inalterados.
- Testes de segurança existentes pertinentes confirmam que a alteração do handler não muda autenticação/autorização.
- A execução da suíte e dos testes direcionados ocorrerá somente depois de autorização da tarefa de implementação; esta rodada não executou testes.

## 5. Riscos e controles

| Risco | Controle no plano |
|---|---|
| Handler genérico expor mensagem interna | Mensagem fixa, não derivada de `Exception.getMessage()`; teste com texto sentinela |
| Handler amplo interferir em respostas de segurança | Verificar origem/ordenação do tratamento e executar regressões de autenticação/autorização |
| Exceção de parsing encapsulada não ser coberta | Testar os dois corpos HTTP; ajustar o tipo tratado apenas com evidência do teste |
| Mudança acidental nos erros atuais | Preservar mapeamentos e testar status, nomes, mensagens e campos existentes |
| Alteração desnecessária do contrato `ApiError` | Não adicionar campos; exigir justificativa concreta para qualquer mudança |

## 6. Dependências e decisões

- Não há dependência técnica de outra iniciativa.
- As decisões funcionais de status e proteção contra vazamento foram fornecidas e consideradas aprovadas para o planejamento.
- Antes de implementar, T-01 deve fixar os textos genéricos exatos e comprovar, por testes HTTP, a exceção de parsing e o limite de tratamento genérico. Isso é detalhamento dentro das decisões aprovadas, não reabertura delas.

## 7. Primeira tarefa de implementação

**T-01 — Confirmar os cenários HTTP e fechar os textos estáveis do contrato.**

- **Dependências:** aprovação explícita da iniciativa e da T-01.
- **Arquivos prováveis:** novos/atuais testes de integração HTTP; possivelmente `GlobalExceptionHandlerTest` para cobrir a resposta do método de erro inesperado, se introduzido nesta tarefa.
- **Trabalho:** criar testes HTTP determinísticos para JSON malformado, JSON incompatível e exceção inesperada controlada; registrar status e forma do corpo atual; fixar textos genéricos de resposta conforme as decisões aprovadas. Não modificar implementação de produção nesta tarefa se o fluxo SDD autorizar uma tarefa de teste/caracterização separada.
- **Critérios de conclusão:** evidência reproduzível dos três cenários via HTTP; confirmação de que `ApiError` comporta as respostas sem adaptação; verificação de que erros de segurança não entram no mapeamento 500; resultados dos testes registrados.

Após T-01, T-02 implementará os mapeamentos mínimos no handler, seguida por regressões e verificação consolidada.

## 8. Resultado da caracterização T-01 — 2026-10-21

Esta seção atualiza as anotações preliminares de que ainda não havia execução de testes:

- Com MockMvc, `GlobalExceptionHandler` e `SecurityConfig` reais, JSON malformado e JSON com tipo incompatível resolvem para `HttpMessageNotReadableException`, recebem status 400 e deixam o corpo vazio. Logo, o mapeamento em `ApiError` está ausente no fluxo MVC atual.
- Uma `IllegalStateException` controlada escapa como `ServletException` no MockMvc. Essa alternativa não executa o error dispatch do container e não demonstra a resposta HTTP externa.
- Ensaios separados com servidor aleatório/PostgreSQL Testcontainers receberam 401 nos caminhos de autenticação e na rota de sonda mesmo com JWT gerado pelo serviço; causa não determinada. Não se alterou segurança para contornar esse resultado.
- Textos propostos para T-02: `Malformed request body` e `An unexpected error occurred.`. O texto de erro inesperado é uma decisão de contrato proposta, não um comportamento já comprovado nem testado em resposta HTTP.
- Comandos/resultados e limitações de cobertura estão no relatório de T-01 em `tasks.md`.

A implementação T-02 deverá acrescentar teste que obtenha a resposta HTTP efetiva de 500 e verifique texto genérico e ausência de detalhes internos. Deve também decidir uma forma de executar o teste por servidor sem alterar a política de segurança de produção.
