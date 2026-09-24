# Seed local

Massa de dados para desenvolvimento: 12 usuários, cada um com um canal de um
segmento diferente (culinária, viagem, corrida, programação, fotografia,
música, pets, plantas, games, arte, café e surf), 60 publicações com imagem,
comentários com respostas, likes e follows entre os canais.

## Quando roda

Sem `SPRING_PROFILES_ACTIVE`, o app sobe com o perfil `local`
(`spring.profiles.default` no `application.yml`). O `application-local.yml`
liga `app.seed.enabled`, e o `LocalContentSeeder` roda no
`ApplicationReadyEvent`, depois de o bucket existir.

Ele só grava quando **as três condições** valem:

1. o perfil `local` está ativo (qualquer perfil informado o desliga);
2. o banco e o MinIO estão em `localhost`;
3. `seed/content.json` está no classpath. O `maven-jar-plugin` exclui
   `seed/**` do jar, então um build de produção não tem o que semear.

Os testes sobem com o perfil `test` (`src/test/resources/config/application.yml`),
então o seed não entra nos bancos dos testes, exceto no
`LocalContentSeederIntegrationTest`, que o ativa de propósito.

## Uma vez só

Os usuários do seed têm `keycloak_id = 'seed:<username>'`. Se algum existe, o
seed não faz nada. Tudo é gravado numa transação; se falhar, as imagens já
enviadas ao MinIO são apagadas e o app continua subindo.

Para refazer do zero, apague os usuários do seed e reinicie o app. O cascade
leva canais, publicações, comentários, likes e follows:

```sql
DELETE FROM users WHERE keycloak_id LIKE 'seed:%';
```

As imagens antigas ficam órfãs no MinIO local, o que não atrapalha.

Os usuários do seed não existem no Keycloak: servem como autores, não para
login. As datas ficam espalhadas pelas últimas semanas, e os canais do seed
também seguem alguns canais que já existiam no banco.

## Conteúdo

- `src/main/resources/seed/content.json`: usuários, canais, legendas e
  comentários. Autores de comentário usam a `key` do canal.
- `src/main/resources/seed/images/<key>/`: `avatar.jpg` (1:1), `banner.jpg`
  (3:1) e `post-N.jpg` (4:3), nos mesmos formatos do recorte do frontend.
- `src/main/resources/seed/CREDITS.md`: autor, licença e fonte de cada imagem
  (CC0, domínio público ou CC BY, via Openverse).

Likes e follows são sorteados com uma semente fixa: todo banco novo recebe a
mesma massa.
