# Especificação: Administração de vínculo User–Customer

**Iniciativa:** `005-user-customer-link-management`  
**Estado:** implementação T-01 a T-04 concluída; iniciativa não finalizada. A T-05 identificou evidência pendente para o CA-15 (requisições de pedidos após mudança de vínculo pelo fluxo novo).
**Base:** decisões de negócio e inspeção iniciadas em 2026-10-22; implementação e evidências por tarefa estão registradas em `tasks.md`. Em 2026-10-23, `mvn "-Dmaven.repo.local=C:/Users/lucas/.m2/repository" clean test` passou com 169 testes, 0 falhas, 0 erros e 0 ignorados.

## 1. Objetivo e problema de negócio

Permitir que um administrador autorizado associe, troque ou remova o Customer de um usuário `USER`, registrando a mudança em auditoria. O vínculo é opcional e usuários `USER` sem vínculo recebem `403` ao acessar operações de pedidos. O registro público continua criando `USER` sem aceitar `customerId`.

## 2. Fatos existentes

- `User.customerId` é um UUID opcional. A migration V4 fornece FK para `customers(id)` e índice não único; vários usuários podem compartilhar um Customer.
- A migration V4 também cria `user_customer_link_audit`, com ator e alvo vinculados a `users`, snapshots dos Customers anterior/novo, ação `CREATE`/`CHANGE`/`REMOVE`, instante e justificativa. Um trigger bloqueia `UPDATE` e `DELETE` na tabela.
- `UserCustomerLinkAudit`, `UserCustomerLinkAction`, `UserCustomerLinkAuditRepository`, `UserCustomerLinkService` e `AdminUserCustomerLinkController` existem. Não há endpoint de consulta do histórico.
- `AuthenticatedUserResolver.resolveCurrent()` localiza o usuário persistido pelo principal JWT e devolve `userId` e `customerId`; `UserCustomerLinkService` consulta também o papel persistido atual do ator.
- `SecurityConfig` usa a role do JWT para autenticação/autorização e habilita method security. A iniciativa requer verificação adicional da role persistida do ator.
- `GlobalExceptionHandler` mapeia `ResourceNotFoundException` a 404, falhas de validação a 400, `IllegalArgumentException` a 400 e exceções inesperadas a 500 genérico. Não existe mapeamento específico para conflito de vínculo.
- `OrderAuthorizationService` permite a `USER` acesso a pedidos somente quando há Customer associado; `ADMIN` tem escopo amplo.

## 3. Decisões de negócio aprovadas para o planejamento

1. Somente ADMIN pode administrar vínculos; além da autorização de segurança existente, o serviço valida que o ator autenticado corresponde a usuário persistido com papel `ADMIN`.
2. Somente contas persistidas com papel `USER` podem ser alvo. Contas ADMIN não podem ter vínculo alterado por esta operação.
3. Solicitar a associação que o usuário já possui é operação sem efeito, sem novo evento de auditoria.
4. A justificativa é obrigatória, não pode ficar vazia após normalização e tem no máximo 500 caracteres.
5. As mudanças usam a auditoria existente. Esta iniciativa não cria endpoint para consultar ou exportar histórico.
6. A API terá operações REST explícitas de associação/troca e remoção.

Essas decisões não aprovam a iniciativa nem sua implementação. A operação administrativa de vínculo foi explicitamente deixada para especificação própria em `002-authorization-policy`.

## 4. Escopo

- Associar um usuário `USER` sem vínculo a um Customer existente.
- Trocar o Customer de um usuário `USER` vinculado.
- Remover o vínculo existente de um usuário `USER`.
- Validar o ator e o alvo usando registros persistidos.
- Obter o ator do principal autenticado; não aceitar `actorUserId` informado pelo cliente.
- Atualizar `users.customer_id` e inserir o evento correspondente na mesma transação.
- Serializar mudanças simultâneas ao vínculo de um mesmo usuário e manter a auditoria consistente com os valores anterior/novo.
- Preservar as regras existentes de autorização de pedidos e o contrato atual de login/registro.

## 5. Fora de escopo

- Associação automática no registro ou por igualdade de e-mail.
- Alterar papéis, credenciais, dados gerais de usuários ou dados gerais de Customers.
- CRUD geral de usuários ou Customers.
- Associar contas ADMIN.
- Consultar, exportar ou administrar retenção do histórico de auditoria.
- Alterar permissões de pedidos, cadastro/login ou outros contratos existentes.
- Nova tabela, migration ou dependência, salvo necessidade demonstrada e aprovada separadamente.

## 6. Contrato REST implementado

Os caminhos abaixo incluem o context path `/api`. A implementação retorna DTOs diretamente; os status e corpos descritos nesta seção foram exercitados pelos testes MVC e PostgreSQL registrados em `tasks.md`.

### Associar ou trocar

`PUT /api/admin/users/{userId}/customer-link`

```json
{
  "customerId": "e59bdf14-902b-4688-9dd6-8078e91f744a",
  "justification": "Alocação do usuário ao cliente aprovada pelo suporte"
}
```

Sucesso: `200 OK`, com representação mínima:

```json
{
  "userId": "8d366ec9-24c9-4d1b-8206-7b13346b8599",
  "customerId": "e59bdf14-902b-4688-9dd6-8078e91f744a",
  "changed": true
}
```

Para repetição da mesma associação, a resposta é `200 OK` com `changed: false`; nenhuma linha de auditoria é criada. `changed` representa a alteração efetiva desta chamada, não um valor da tabela de auditoria.

### Remover

`POST /api/admin/users/{userId}/customer-link/removal`, com corpo:

```json
{
  "justification": "Desassociação solicitada e aprovada"
}
```

O uso de `POST` mantém a justificativa no corpo. A resposta é `200 OK` com a mesma representação da associação, `customerId: null` e `changed: true` para remoção efetiva:

```json
{
  "userId": "8d366ec9-24c9-4d1b-8206-7b13346b8599",
  "customerId": null,
  "changed": true
}
```

Se o vínculo já estiver ausente, remoção é idempotente: `200 OK`, `customerId: null`, `changed: false` e nenhum novo evento.

### Respostas e casos sem decisão de contrato

- Sem autenticação: `401` pelo mecanismo de segurança existente.
- Autenticado sem autoridade ADMIN: `403` pelo mecanismo existente.
- Usuário ou Customer não encontrado: `404` via `ResourceNotFoundException`; texto exato para estes novos casos deve ser definido na T-01, mantendo o formato `ApiError` atual.
- Justificativa ausente/inválida ou corpo ilegível: `400` no contrato atual de validação/desserialização e `ApiError`.
- Falha inesperada de persistência/auditoria: `500` genérico existente, com rollback da transação.
- Tentativa de modificar alvo ADMIN retorna `400 Bad Request` com `ApiError`, usando o tratamento atual de `IllegalArgumentException`.
- JWT válido cujo subject não tem usuário persistido retorna `500` genérico nos endpoints de vínculo, conforme observado na T-03; este comportamento não foi alterado.

## 7. Regras de validação implementadas

- `userId`, `customerId` e ator são UUIDs persistidos; o ator vem do principal autenticado e não aparece em nenhum campo de entrada.
- O usuário alvo precisa existir e ter papel `USER` no banco.
- O Customer de destino precisa existir no banco. A FK continua sendo a última garantia de integridade referencial.
- A justificativa é obrigatória e não vazia após `String.strip()`; deve ter no máximo 500 caracteres após `strip()` e é persistida normalizada, preservando espaços internos. Os DTOs fazem strip antes de `@NotBlank`/`@Size(max=500)` e o serviço valida e normaliza novamente.
- Associar o Customer já associado não altera usuário nem auditoria.
- Para associação nova, gravar `CREATE`, anterior `NULL`, novo igual ao Customer solicitado.
- Para troca, gravar `CHANGE`, anterior igual ao vínculo persistido e novo igual ao solicitado.
- Para remoção efetiva, gravar `REMOVE`, anterior igual ao vínculo persistido e novo `NULL`.
- Para remoção repetida de vínculo ausente, retornar `changed=false`, sem evento.

## 8. Auditoria, transação e concorrência

O evento registra ator persistido, alvo, snapshots anterior/novo, ação, instante UTC preenchido pela entidade/banco e justificativa. A requisição não pode escolher ator, timestamps, ação ou vínculo anterior. O histórico permanece append-only e não será exposto por endpoint nesta iniciativa.

Atualização do usuário e inserção do evento são atômicas: teste PostgreSQL com falha real em `BEFORE INSERT` de auditoria confirmou rollback do vínculo. Alterações concorrentes são serializadas pelo `PESSIMISTIC_WRITE` de `UserRepository.findByIdForUpdate`; teste PostgreSQL observou a espera e os backends bloqueador/bloqueado e verificou snapshots compatíveis. A entidade `User` não declara `@Version`.

## 9. Critérios de aceitação

- **CA-01 — Autenticação:** requisição anônima às operações recebe `401` e não altera usuário nem auditoria.
- **CA-02 — Autorização por token:** usuário autenticado sem autoridade ADMIN recebe `403` e não altera dados.
- **CA-03 — Ator persistido:** role ADMIN no JWT não é suficiente se o papel persistido deixou de ser ADMIN; `actor_user_id` é o UUID do ADMIN persistido resolvido do principal.
- **CA-04 — Alvo elegível:** somente usuário persistido com role USER pode ser alvo. Alvo ADMIN é rejeitado com `400 ApiError`; usuário e auditoria permanecem inalterados.
- **CA-05 — IDs existentes:** usuário alvo e Customer de destino inexistentes não causam alteração de vínculo nem evento de auditoria e usam o tratamento 404 existente.
- **CA-06 — Associação:** usuário USER sem vínculo é associado ao Customer indicado e um evento `CREATE` correto é persistido.
- **CA-07 — Troca:** vínculo existente é substituído e um evento `CHANGE` contém os UUIDs anterior/novo corretos.
- **CA-08 — Remoção:** vínculo existente vira `NULL` e um evento `REMOVE` contém Customer anterior e `new_customer_id=NULL`; sucesso `200` com DTO de resultado.
- **CA-09 — Repetição de associação:** associar novamente o mesmo Customer não altera vínculo nem quantidade/conteúdo de eventos de auditoria.
- **CA-09a — Repetição de remoção:** remover vínculo ausente retorna `200`, `changed=false` e não cria evento.
- **CA-10 — Justificativa:** valor ausente/vazio após `strip()` ou acima de 500 caracteres após `strip()` é rejeitado com `400`; valor aceito é persistido após `strip()`, preservando espaços internos.
- **CA-11 — Entrada não confiável:** o contrato não aceita `actorUserId`, papel, vínculo anterior, ação ou instante fornecidos pelo cliente.
- **CA-12 — Atomicidade:** falha de persistência do evento reverte também a alteração em `users.customer_id`.
- **CA-13 — Concorrência:** mudanças concorrentes ao mesmo usuário produzem uma sequência serializável de vínculo e eventos, sem evento com snapshot anterior incompatível com o valor substituído.
- **CA-14 — Auditoria imutável:** o fluxo apenas insere eventos; os mecanismos existentes de append-only continuam impedindo UPDATE/DELETE.
- **CA-15 — Compatibilidade de autorização:** após associação/troca/remoção, a política existente de pedidos usa o Customer persistido atualizado em requisições subsequentes; nenhuma associação ocorre no registro ou por e-mail.
- **CA-16 — Contrato HTTP:** entradas inválidas usam `ApiError` 400 e recursos inexistentes usam `ApiError` 404 conforme handler atual; sucessos retornam `200` com DTO de resultado; alvo ADMIN recebe `400`; remoção sem vínculo é `200 changed=false` sem auditoria.

## 10. Decisões contratuais consolidadas

1. `PUT /api/admin/users/{userId}/customer-link` associa/troca e `POST /api/admin/users/{userId}/customer-link/removal` remove; ambos retornam `200` e `{userId, customerId, changed}`.
2. Repetir associação e remover vínculo ausente são operações sem efeito e sem auditoria.
3. O papel ADMIN é exigido no JWT pela rota e no registro persistido pelo serviço; o alvo persistido deve ser USER.
4. Justificativa é normalizada com `String.strip()`, obrigatória e limitada a 500 caracteres após normalização.
5. Ações `CREATE`, `CHANGE` e `REMOVE` guardam ator persistido, alvo, snapshots anterior/novo e justificativa. A gravação do vínculo e da auditoria ocorre na mesma transação.

## 11. Fechamento da T-05 e pendência

A suíte completa passou em 2026-10-23 (169 testes). Os demais critérios têm evidências unitárias, MVC ou PostgreSQL/Testcontainers registradas em `tasks.md`. A T-05 não declara a iniciativa integralmente finalizada porque o CA-15 ainda não tem um teste que, após cada associação/troca/remoção pelo fluxo novo, faça uma requisição de pedidos com JWT e confirme o escopo atualizado. Os testes existentes de autorização de pedidos exercitam usuários já vinculados, sem realizar essas alterações antes da requisição. T-05 não pode adicionar esse teste sob seu escopo documental autorizado; a lacuna está registrada para decisão/autorização futura.

Limitações confirmadas: testes de endpoint usam `MockMvc`, não socket HTTP; JWT válido com subject sem usuário persistido produz `500` genérico; não há endpoint de consulta do histórico de auditoria. Nenhuma dessas limitações foi alterada na T-05.
