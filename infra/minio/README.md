# MinIO local e dos testes

Os registries oficiais passaram a recusar downloads das imagens em setembro de 2026; os binários arquivados também retornam HTTP 410. Um cache Docker existente escondia a falha que apareceu no CodeBuild.

Este Dockerfile compila o código oficial da **mesma release já usada localmente**, `RELEASE.2025-09-07T16-13-09Z`, commit `07c3a429bfed433e49018cb0f78a52145d4bedeb`. O download é verificado por SHA-256; as imagens base oficiais Go e Debian também possuem digest fixo. A licença upstream acompanha a imagem.

O Compose e os testes compartilham esse arquivo. Não há registry próprio, ECR ou novo serviço AWS. O primeiro build baixa o compilador/dependências e leva alguns minutos; os seguintes aproveitam as camadas locais do Docker. No CodeBuild efêmero esse tempo aumenta o custo de cada execução. Em produção a aplicação usa Amazon S3.

Da raiz do projeto:

```bash
docker compose -f docker-compose.dev.yml build minio
docker compose -f docker-compose.dev.yml up -d minio
./mvnw clean verify
```

Os testes constroem a imagem automaticamente. A alteração não apaga volumes nem reinicia containers existentes até executar `up`. Não execute `down -v` para atualizar a imagem: isso apagaria os dados locais.

Fontes: [código e instruções oficiais de build](https://github.com/minio/minio), [release usada](https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z), [ImageFromDockerfile do Testcontainers](https://java.testcontainers.org/features/creating_images/).
