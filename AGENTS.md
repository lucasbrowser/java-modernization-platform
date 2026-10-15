# Instruções de desenvolvimento

## Contexto do projeto
- Plataforma de modernização de sistemas Java.
- Java 21.
- Spring Boot 3.3.5.
- Maven.
- PostgreSQL.
- Flyway para versionamento do banco.
- Spring Data JPA.
- Spring Security e JWT.
- Docker e Docker Compose.

## Arquitetura
- Preservar a arquitetura de monólito modular existente.
- Respeitar os módulos e as dependências já estabelecidos.
- Evitar acoplamento desnecessário entre auth, customer, product e order.
- Não introduzir microsserviços sem uma especificação aprovada.
- Reutilizar padrões e componentes existentes.

## Processo obrigatório
1. Ler a especificação antes de implementar.
2. Inspecionar o código existente e identificar impactos.
3. Elaborar ou atualizar o plano técnico.
4. Dividir o trabalho em tarefas pequenas.
5. Implementar uma tarefa por vez.
6. Criar ou atualizar testes automatizados.
7. Executar os testes pertinentes.
8. Comparar a implementação com os critérios de aceitação.
9. Informar arquivos alterados, testes executados e limitações.

## Qualidade de código
- Seguir as convenções Java existentes.
- Manter imports organizados e código formatado.
- Evitar duplicação e abstrações desnecessárias.
- Preservar compatibilidade com Java 21.
- Não alterar contratos de API sem autorização da especificação.

## Banco de dados
- Usar migrations Flyway para alterações de schema.
- Não modificar migrations já aplicadas sem justificativa explícita.
- Considerar integridade referencial e compatibilidade com dados existentes.

## Testes e segurança
- Executar `mvn clean test` quando apropriado.
- Criar testes unitários e de integração conforme o comportamento alterado.
- Não remover testes para fazer a compilação passar.
- Não enfraquecer autenticação, autorização ou validações.
- Não incluir segredos, tokens ou senhas no código.

## Git
- Não descartar alterações existentes do usuário.
- Fazer commits pequenos e descritivos, usando Conventional Commits.
- Não fazer push nem publicar alterações sem autorização explícita.
- Não criar commits automaticamente sem solicitar autorização.