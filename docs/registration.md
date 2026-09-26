# Cadastro e Keycloak por código

O frontend oferece `/register` (também `/cadastro`) e o link **Criar conta**
no login. O botão abre o formulário do serviço de contas com o tema Naro,
em português. O formulário original do Keycloak mantém suas validações,
proteção de sessão e mensagens de erro; o tema altera somente CSS e textos.

O cadastro usa o mesmo Authorization Code + PKCE S256, `state`, `nonce` e
callback do login. A senha é enviada diretamente ao Keycloak. O Naro não
armazena senhas nem expõe credenciais administrativas no frontend/backend.
Depois do callback, o backend provisiona o `User` pelo `sub` real do JWT.
Primeiros acessos simultâneos usam INSERT idempotente no PostgreSQL.

## Configurar sem o console administrativo

Na raiz do backend:

```bash
./scripts/keycloak-setup.sh
```

O mesmo comando está disponível na raiz do frontend pelo wrapper
`./scripts/keycloak-setup.sh`. Ele sobe o Keycloak local, executa a configuração
idempotente e sincroniza o `.env.local` do frontend com permissão `0600`, sem
imprimir o client secret. Mantém usuários, roles, demais clients e o secret
atual. Por padrão espera os projetos irmãos `naro` e `interface-da-rede-naro`.
Para outra localização, informe `NARO_FRONTEND_DIR` no backend ou
`NARO_BACKEND_DIR` no frontend.

O `docker-compose.dev.yml` também executa o serviço `keycloak-setup` ao subir
a infraestrutura completa. O script é recomendado para sincronizar o `.env.local`
e os limites de perfil com a entity `User`.

Arquivos mantidos em código:

- `infra/keycloak/realm-settings.json`: cadastro aberto, email único, senha
  com pelo menos 12 caracteres e diferente do username/email, proteção contra
  tentativas repetidas de login, idioma e tema.
- `infra/keycloak/configure.sh`: aplicação idempotente dessas configurações
  e do client confidencial `naro-web`, com callback/logout restritos,
  PKCE obrigatório, sem implicit flow, password grant ou service account.
- `infra/keycloak/themes/naro`: apresentação do formulário hospedado.
- `scripts/keycloak-setup.sh` e `scripts/sync-keycloak-env.py`: execução local,
  limites de perfil (username/nomes 100, email 255) e configuração do frontend.

É preciso ter uma credencial administrativa **de infraestrutura** para
configurar um realm existente. O ambiente local usa os defaults do Compose;
para outra credencial, informe `KEYCLOAK_ADMIN` e `KEYCLOAK_ADMIN_PASSWORD`.
Sem essa permissão, nenhum código pode habilitar o cadastro de um realm que
o proíbe. Essas credenciais são usadas apenas pelo setup, em arquivos
temporários apagados ao finalizar, e nunca pelo Naro em execução.

## Email e ambientes fora do local

O ambiente local permite testar o cadastro sem servidor de email. Ele não
afirma que o endereço foi verificado. Um `verifyEmail` já habilitado no realm
é preservado.

Para configurar envio e exigir verificação por código, forneça SMTP antes de
executar o setup:

```bash
export NARO_SMTP_HOST='smtp.seu-provedor.com'
export NARO_SMTP_FROM='contas@seu-dominio.com'
export NARO_SMTP_USER='usuario-smtp'
export NARO_SMTP_PASSWORD='<segredo-smtp>'
./scripts/keycloak-setup.sh
```

Defaults: porta 587, STARTTLS habilitado e SSL direto desabilitado. Podem ser
alterados com `NARO_SMTP_PORT`, `NARO_SMTP_STARTTLS` e `NARO_SMTP_SSL`.
Esse caminho configura `smtpServer` e `verifyEmail=true` via Admin API,
sem colocar o segredo em argumentos de linha de comando nem no repositório.

O Compose fornecido é de desenvolvimento. Em produção, use HTTPS, credenciais
próprias, configuração de Keycloak para produção e limitação de requisições
no ingresso. O fluxo de cadastro não exige um endpoint público com credenciais
administrativas no backend.

## Testar com duas contas

1. Suba a API atualizada para aplicar a migration V11 que remove os antigos
   usuários `friend-seed:*`; ela conserva as contas reais.
2. Abra `/register` e crie uma conta real no formulário Naro/Keycloak.
3. Use outro perfil de navegador ou saia da conta e crie a segunda conta.
4. Abra `/friends`, procure a outra pessoa e clique **Adicionar**.
5. Na outra conta, a solicitação recebida aparece ao abrir a aba, recuperar
   foco ou na atualização periódica. Aceite, rejeite ou cancele pelo remetente.
6. Depois da aceitação, ambas as contas encontram a amizade; remover preserva
   os usuários e o histórico.

Referências oficiais: [cadastro e proteção do realm](https://www.keycloak.org/docs/latest/server_admin/),
[Admin REST API](https://www.keycloak.org/docs-api/26.7.4/rest-api/index.html) e
[temas](https://www.keycloak.org/ui-customization/themes).
