# Validação do laboratório Naro

Execução em 26/09/2026. Este arquivo separa testes executados de etapas ainda pendentes; `README.md` explica arquitetura e operação.

| Verificação | Resultado |
|---|---|
| Backend local | JAR novo iniciou com profile local; health200, API protegida401, Swagger200 |
| Keycloak local | Discovery do realm social respondeu200; Compose preservado |
| MinIO local | Health200; testes reais de upload/presigned passaram |
| Maven backend | 326 testes, zero falhas/erros/skips |
| CDK Java | Compilação e 8 testes passaram |
| CDK synth/diff | Executados e revisados |
| Scripts | 7 testes Python; sintaxe shell e empacotamento verificados |
| Runtime ARM | Keycloak26.7.3/PostgreSQL17 testados em containers isolados, configuração idempotente |
| Infraestrutura AWS | NaroLab CREATE_COMPLETE em us-east-1; EC2 ARM e RDS na us-east-1a |
| Target Groups | Aguardando aplicação |
| Spring → RDS | Aguardando teste AWS |
| Keycloak → RDS | Aguardando teste AWS |
| Spring → S3 por IAM Role | Aguardando smoke da API |
| S3 privado | Configuração de Block Public Access e AES256 verificada; teste de objeto pela API ainda pendente |
| RDS privado | PubliclyAccessible=false, SingleAZ, gp3 20GiB, criptografia, backup1 e monitoring0 confirmados |
| Push → CodePipeline | Aguardando execução após criação |
| CodeBuild | Primeira execução detectou imagem MinIO indisponível e precisão de timestamp em um teste; correções em validação |
| CodeDeploy/systemd | Aguardando execução |
| Backend/Keycloak pelo ALB | Aguardando aplicação |
| Secrets no Git | Gitleaks no histórico e staged sem credenciais reais; único falso positivo era endpoint JDBC local, ignorado por expressão exata |

A primeira tentativa encontrou uma validação específica do CodeDeploy: ele testa permissões no ASG fictício `CodeDeployTestAPICall`. O CloudTrail confirmou o motivo. O CDK foi corrigido com esse ARN específico, sem `AdministratorAccess` e sem liberar todas as ações. O rollback ocorreu antes de qualquer deploy/dado de aplicação. Após aviso, os buckets vazios e o snapshot inicial retidos dessa tentativa foram removidos para evitar duplicações; nenhum dado anterior do usuário foi apagado.

Após a criação, `cdk diff --profile naro-cdk --no-change-set --no-lookups` retornou **There were no differences**. EC2 com IMDSv2 obrigatório, EBS gp3 20GiB criptografado e créditos Standard. Bootstrap concluído; agentes CodeDeploy e SSM e serviço Docker ativos. ASG mínimo/desejado/máximo 1 e nenhum NAT Gateway.

A instalação GitHub “AWS Connector for GitHub” foi confirmada com **Only select repositories → naro_back**. A conexão `leo-git` e o Budget existente de US$15 são reutilizados; nenhum PAT ou novo segredo GitHub foi criado.
