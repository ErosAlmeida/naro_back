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
| Target Groups | Backend e Keycloak `healthy` após o deploy `d-5D79KZCXL` |
| Spring → RDS | Flyway aplicado; `/actuator/health` pelo ALB retornou `UP` (inclui o indicador do banco) |
| Keycloak → RDS | Keycloak pronto; realm `social` e tema servidos pelo ALB |
| Spring → S3 por IAM Role | Pendente: `smoke_application.py` via SSM (upload real) |
| S3 privado | Configuração de Block Public Access e AES256 verificada; teste de objeto pela API ainda pendente |
| RDS privado | PubliclyAccessible=false, SingleAZ, gp3 20GiB, criptografia, backup1 e monitoring0 confirmados |
| Push → CodePipeline | Push na `master` dispara Source → Build → Deploy; execução do commit `fc6e07d` concluída |
| CodeBuild | 326 testes, zero falhas, em ~8 min (MinIO compilado no build) |
| CodeDeploy/systemd | Todos os hooks `Succeeded`; Spring e Keycloak ativos via systemd |
| Backend/Keycloak pelo ALB | health 200 `UP`; OpenAPI 200 (42 paths); API sem token 401; `/auth/admin` e realm `master` 403; discovery com issuer público |
| Secrets no Git | Gitleaks no histórico e staged sem credenciais reais; único falso positivo era endpoint JDBC local, ignorado por expressão exata |

A primeira tentativa encontrou uma validação específica do CodeDeploy: ele testa permissões no ASG fictício `CodeDeployTestAPICall`. O CloudTrail confirmou o motivo. O CDK foi corrigido com esse ARN específico, sem `AdministratorAccess` e sem liberar todas as ações. O rollback ocorreu antes de qualquer deploy/dado de aplicação. Após aviso, os buckets vazios e o snapshot inicial retidos dessa tentativa foram removidos para evitar duplicações; nenhum dado anterior do usuário foi apagado.

Após a criação, `cdk diff --profile naro-cdk --no-change-set --no-lookups` retornou **There were no differences**. EC2 com IMDSv2 obrigatório, EBS gp3 20GiB criptografado e créditos Standard. Bootstrap concluído; agentes CodeDeploy e SSM e serviço Docker ativos. ASG mínimo/desejado/máximo 1 e nenhum NAT Gateway.

A instalação GitHub “AWS Connector for GitHub” foi confirmada com **Only select repositories → naro_back**. A conexão `leo-git` e o Budget existente de US$15 são reutilizados; nenhum PAT ou novo segredo GitHub foi criado.

## Primeiro deploy completo

Quatro problemas apareceram só na AWS e foram corrigidos em código:

1. **Imagens MinIO removidas upstream.** `infra/minio/Dockerfile` compila a release fixada a partir do código oficial.
2. **Limite de pulls anônimos do Docker Hub no CodeBuild** (`toomanyrequests`), que tem IPs compartilhados. Postgres, Go e Debian passaram a vir do espelho oficial **ECR Public** (`public.ecr.aws/docker/library`, mesmos digests, sem custo). O Ryuk, que só existe no Docker Hub, fica desligado apenas no container efêmero do CodeBuild.
3. **Docker-in-Docker do CodeBuild.** O Testcontainers anunciava o IP da bridge, e a trava do seed local (só aceita localhost) pulava o seed. `TESTCONTAINERS_HOST_OVERRIDE=localhost` foi definido só no `buildspec.yml`; a trava do seeder continua intacta.
4. **O master do RDS não é superusuário.** O PostgreSQL recusa qualquer menção a `SUPERUSER`/`REPLICATION` vinda dele, mesmo para desligar esses atributos. `configure_runtime.py` agora usa apenas atributos concedíveis. Reproduzido e validado localmente em PostgreSQL 17 com um admin sem superuser, incluindo idempotência e isolamento entre `naro_app` e `keycloak`.

O realm ganhou o client público `naro-postman` (PKCE S256, apenas callbacks fixos do Postman). A coleção e o environment ficam em `docs/postman/`, gerados a partir do `/v3/api-docs` de produção.

Pendente: executar `smoke_application.py` na EC2 via SSM para o teste real de upload/download no S3.
