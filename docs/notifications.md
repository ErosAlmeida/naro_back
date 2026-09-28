# Notificações do chat

```
Naro aberto            →  WebSocket/STOMP (mensagem, badge, toast)
Naro fechado/fundo     →  Web Push (notificação do sistema)
Conversa na tela       →  nada (nem push, nem toast)
```

Quando alguém manda uma mensagem, o destinatário é avisado como num app de
mensagens: dentro do Naro aberto, pelo WebSocket; com o Naro fechado,
minimizado ou em segundo plano (inclusive o app instalado no Android e no
iPhone), por uma notificação do sistema via Web Push. Quem está com a conversa
na tela não recebe push dela, e tocar numa notificação abre a conversa certa.

Nada disso muda o chat: a mensagem é salva e entregue exatamente como antes.
O push acontece **depois** do commit, em outra thread, e qualquer falha nele
vira só uma linha de log.

## 1. Arquitetura

```
             ┌─────────────── Spring (api.naro.api.br) ───────────────┐
 navegador   │                                                        │
 ─────────── │  ChatWebSocketController ── ChatServiceImpl            │
 STOMP /ws ──┤    /app/chat/send            salva a mensagem          │
             │    /app/chat/focus           foco + leitura            │
             │                              │ ChatMessageCreatedEvent │
             │              AFTER_COMMIT ───┼──────────────┐          │
             │  ChatRealtimePublisher       │              │          │
 ◄───────────┤    /user/queue/chat (mensagem, só na área do chat)     │
 ◄───────────┤    /user/queue/notifications (só metadados, toda tela) │
             │                              ▼              ▼          │
             │                     NotificationService (thread push-*)│
             │                       quem recebe? (foco, leitura)     │
             │                       o que mostrar? (modo de prévia)  │
             │                              ▼                         │
             │                     PushNotificationService            │
             │                       todos os aparelhos do usuário    │
             │                       limpa inscrição expirada/recusada│
             │                              ▼                         │
             │                     WebPushClient                      │
             │                       RFC 8291 (cifra) + VAPID (8292)  │
             └──────────────────────────────┼─────────────────────────┘
                                            ▼  HTTPS
                          push service do navegador (FCM, Mozilla, Apple, WNS)
                                            ▼
                                 public/sw.js (evento push) → notificação
```

| Peça | Onde | Papel |
|---|---|---|
| `PushSubscription` | `notification/domain` | Um navegador/aparelho: endpoint, p256dh, auth, nome do aparelho, `enabled`, datas |
| `PushSubscriptionService` / `PushSubscriptionController` | `notification` | Cadastro, lista e remoção, sempre do usuário do JWT |
| `NotificationService` | `notification` | Decide quem recebe push de uma mensagem e com qual conteúdo |
| `PushNotificationService` | `notification` | Entrega a todos os aparelhos do usuário e mantém a lista limpa |
| `WebPushClient`, `WebPushEncryption`, `VapidCredentials`, `PushEndpointPolicy` | `notification/webpush` | Protocolo Web Push só com a JDK (sem dependência nova) |
| `ConversationFocusRegistry` | `chat` | Qual conversa cada sessão WebSocket está mostrando (em memória, como a presença) |
| `conversation_reads` | banco | Até onde cada participante leu cada conversa: base das não lidas e do badge |
| `public/sw.js` | frontend | Mostra a notificação e trata o clique |
| `ChatNotificationsProvider` | frontend | Badge, toast, badge do ícone do app e sincronização da inscrição |

Não existe entidade `Notification` nem `NotificationController`: o único tipo
de aviso é "mensagem nova", e as não lidas saem de `conversation_reads` +
`chat_messages`. Uma caixa de notificações persistida seria uma segunda fonte
da mesma informação. Se surgirem outros tipos (pedido de amizade, menção), o
lugar natural é `NotificationService` + `PushNotificationPayload.type`.

O Web Push é implementado com a própria JDK (ECDH, HKDF, AES-GCM, ES256):
as bibliotecas Java disponíveis estão sem manutenção e arrastam BouncyCastle e
jose4j antigos. A cifra é testada contra o vetor oficial do RFC 8291 (seção 5),
byte a byte, e contra uma decifragem "do lado do navegador".

## 2. Fluxo de uma mensagem até a notificação

1. A envia `/app/chat/send`. `ChatServiceImpl` valida (amizade, E2EE), salva e
   publica `ChatMessageCreatedEvent` — como sempre foi.
2. Depois do commit:
   - `ChatRealtimePublisher` entrega a mensagem em `/user/queue/chat` (igual a
     antes) e um aviso **sem conteúdo** em `/user/queue/notifications`;
   - `NotificationService` agenda o push na fila `push-*` e retorna na hora.
     Fila cheia descarta o push (com log), nunca trava o WebSocket.
3. Na thread `push-*`, para cada participante que não é o remetente:
   - alguma sessão dele (qualquer aparelho) está com a conversa na tela? → sem push;
   - ele já leu a conversa depois da mensagem? → sem push;
   - senão monta o payload e envia a todos os aparelhos ativos dele.
4. Resposta do push service por aparelho:
   - `201` → entregue (`last_used_at`, no máximo uma escrita por hora);
   - `404`/`410` → inscrição expirada: **apagada**;
   - `400`/`401`/`403` → recusada (ex.: outra chave VAPID): **desativada** até o
     navegador se cadastrar de novo;
   - rede, `413`, `429`, `5xx` → nada muda; a próxima mensagem tenta de novo.
5. `public/sw.js` recebe o `push`, valida o payload, mostra a notificação
   (uma por conversa, `tag = chat:<id>`), atualiza o badge do ícone e confirma
   a entrega (`POST /api/chats/<id>/delivered` no BFF): quem enviou passa a ver
   ✓✓ mesmo com o Naro fechado deste lado. Sem sessão ou sem rede, a
   confirmação sai quando o Naro abrir. Veja "Tiques" em `docs/chat.md`.
6. Clique: fecha a notificação, foca uma janela do Naro já aberta e pede à
   página para abrir `/chat/<id>` sem recarregar (não perde o desbloqueio do
   E2EE). Sem janela aberta, abre uma nova direto na conversa.

Nunca geram push: digitação, presença, heartbeat, leitura, erros do WebSocket
e as próprias mensagens. Só `ChatMessageCreatedEvent` chega ao `NotificationService`.

### "Conversa na tela" e leitura

O frontend envia `/app/chat/focus {conversationId}` quando a conversa está
**visível e em foco** e `{conversationId: null}` quando a aba é escondida,
perde o foco ou sai da conversa. Mudanças próximas são agrupadas (150 ms) e o
foco é reenviado a cada reconexão. O servidor:

- só aceita a conversa se o usuário participa dela (senão apenas limpa o foco,
  sem erro);
- guarda o foco por sessão STOMP e o apaga ao desconectar (inclusive por falta
  de heartbeat);
- marca a conversa como lida (`conversation_reads`) e avisa as outras
  abas/aparelhos do mesmo usuário (`CONVERSATION_READ`), que zeram o contador.

Chegou mensagem na conversa aberta? O frontend reenvia o foco, e ela fica lida.

O foco vale para todos os aparelhos: vendo a conversa no notebook, o celular
não toca por ela (como no Telegram). É estado em memória, como a presença: com
mais de uma instância do backend, os dois precisam ir para um store
compartilhado (ex.: Redis).

## 3. WebSocket x Web Push

| | WebSocket/STOMP | Web Push |
|---|---|---|
| Quando | Naro aberto (qualquer tela) | Naro fechado, minimizado, em segundo plano |
| Conteúdo | `/user/queue/chat`: a mensagem (só na área do chat, e no E2EE só com a sessão em READY). `/user/queue/notifications`: metadados | Payload cifrado para o navegador (RFC 8291) |
| Mostra | Mensagem na conversa, contador na lista e no menu, toast fora do chat | Notificação do sistema |
| Depende de | Conexão aberta | Permissão do navegador + inscrição cadastrada |

Os dois convivem: com a aba aberta mas escondida (outra aba, app minimizado),
o foco é `null`, então o push sai; ao voltar, o WebSocket atualiza tudo.

### Payload do push

```json
{
  "type": "CHAT_MESSAGE",
  "conversationId": "…",
  "messageId": "…",
  "sender": { "displayName": "Leonardo", "avatarUrl": null },
  "preview": "Você vai entrar hoje?",
  "url": "/chat/…",
  "createdAt": "2026-09-27T12:00:00Z",
  "unreadConversations": 2
}
```

Nenhum token, e-mail ou id de usuário. `NARO_PUSH_PREVIEW` escolhe o que vai:

| Modo | Título | Texto |
|---|---|---|
| `NAME_AND_PREVIEW` (padrão) | Nome de quem escreveu | Prévia (1 linha, até 120 caracteres) |
| `NAME_ONLY` | Nome | "Nova mensagem" / "3 novas mensagens" |
| `GENERIC` | "Naro" | "Você recebeu uma nova mensagem" |

**Mensagens E2EE nunca têm prévia**, em nenhum modo: o servidor não tem o
texto. Hoje isso inclui produção assim que `E2EE_REQUIRED=true`. O modo está
pronto para virar preferência de cada usuário (basta ler a preferência no
`NotificationService#payload`).

## 4. Chaves VAPID

VAPID identifica o servidor perante os push services. A chave **pública** vai
para o navegador (`applicationServerKey`); a **privada** fica só no ambiente
do Spring e só assina um JWT curto (12 h) por push service.

```bash
./scripts/generate-vapid-keys.sh mailto:voce@seudominio.com
```

Imprime as três variáveis. Usa só o `openssl`; a chave privada não é gravada
em disco fora de um diretório temporário apagado na saída.

- Gere **um par por ambiente** (local e produção separados).
- Não troque a chave de produção sem motivo: inscrições feitas com a chave
  antiga passam a ser recusadas (o servidor as desativa) e cada navegador se
  inscreve de novo sozinho na próxima vez que alguém abrir o Naro.
- Chave ausente ou inválida desliga **só** o Web Push (log de erro, sem o
  valor); o resto do backend sobe normalmente.

## 5. Variáveis de ambiente

Backend (Spring, `naro.push.*` em `application.yml`):

| Variável | Obrigatória | Exemplo | Descrição |
|---|---|---|---|
| `NARO_PUSH_VAPID_PUBLIC_KEY` | para ligar o push | `BCC3nM8I…` | Ponto P-256 não comprimido, Base64url |
| `NARO_PUSH_VAPID_PRIVATE_KEY` | para ligar o push | *(segredo)* | Escalar P-256, Base64url. Nunca no Git, no frontend ou em log |
| `NARO_PUSH_VAPID_SUBJECT` | para ligar o push | `mailto:ops@naro.api.br` ou `https://naro.api.br` | Contato exigido pelos push services (a Apple recusa outros formatos) |
| `NARO_PUSH_PREVIEW` | não | `NAME_AND_PREVIEW` | `NAME_AND_PREVIEW`, `NAME_ONLY` ou `GENERIC` |

Opções sem variável própria (podem ir em `application.yml` ou via
`NARO_PUSH_*` pelo relaxed binding do Spring):

| Propriedade | Padrão | Descrição |
|---|---|---|
| `naro.push.allowed-endpoint-hosts` | FCM, Mozilla, Apple, `*.notify.windows.com` | Push services aceitos (proteção contra SSRF) |
| `naro.push.ttl` | `24h` | Quanto tempo o push service guarda a mensagem de um aparelho offline |
| `naro.push.max-subscriptions-per-user` | `10` | Aparelhos por conta; acima disso sai o usado há mais tempo |

Frontend: **nenhuma variável nova**. A chave pública vem do Spring em tempo
de execução (`GET /api/push/config` no BFF). Nunca crie `NEXT_PUBLIC_` com
chave de push.

## 6. Testar localmente

Sem as variáveis, o push fica desligado e o resto funciona igual (a linha em
Configurações mostra "Indisponíveis no momento").

1. Gere um par **local**: `./scripts/generate-vapid-keys.sh mailto:dev@localhost.test`.
2. Coloque as três variáveis no run configuration do IntelliJ
   (`NaroApplication` → Environment variables) ou exporte no terminal antes de
   `./mvnw spring-boot:run`. Reinicie o backend: o log mostra
   `Web Push enabled (VAPID subject …)` e o Flyway aplica V13 e V14.
3. Frontend: `pnpm dev` já basta — ao ativar, o service worker é registrado
   na hora (no `next dev` ele não é registrado ao carregar a página). Para o
   comportamento idêntico ao de produção, `pnpm build && pnpm start`.
4. Abra `http://localhost:3000` no Chrome, Edge ou Firefox (localhost conta
   como contexto seguro), entre e toque em **Conversas → Ativar notificações**
   ou **Configurações → Notificações de mensagens → Ativar**.
5. Numa janela anônima (ou outro navegador), entre com outra conta amiga e
   mande uma mensagem. Com a aba do primeiro usuário minimizada ou em outra
   aba, a notificação do sistema aparece; com a conversa aberta, não.

Dicas:

- O Chrome só entrega push com o navegador aberto (pode estar sem janelas no
  macOS). No macOS, libere as notificações do Chrome em Ajustes do Sistema →
  Notificações.
- `chrome://gcm-internals` mostra as mensagens que chegaram ao navegador.
- DevTools → Application → Service Workers → **Push** dispara um push de teste
  (vira a notificação genérica, porque o texto não é um payload do Naro).
- O Safari do macOS também funciona em localhost.
- O navegador embutido de ferramentas (Electron) costuma bloquear notificações:
  a interface mostra "Bloqueadas no navegador" e o resto segue normal.

## 7. Produção

O backend lê as variáveis do `/etc/naro/backend.env`, gerado a cada deploy
pelo `aws/scripts/configure_runtime.py` a partir do Parameter Store. O push é
**opcional**: sem os três parâmetros, o deploy segue como antes e o push fica
desligado. Ordem recomendada:

1. **Deploy do backend** (push em `master`, CodeBuild). Aplica V13 e V14:
   tabelas novas e o marcador de leitura inicial de toda conversa existente
   (nada antigo aparece como não lido). Nenhuma tabela ou migration existente
   muda.
2. **Deploy do frontend** (Vercel). Funciona mesmo antes do passo 3.
3. **Permissão IAM** para os novos parâmetros (já no `NaroStack`):

   ```bash
   cd aws && cdk deploy
   ```

4. **Chaves de produção** no Parameter Store (gere um par novo, só para produção):

   ```bash
   ./scripts/generate-vapid-keys.sh https://naro.api.br
   aws ssm put-parameter --region us-east-1 --profile naro-cdk --name /naro/prod/push/vapid-public-key --type String --value '<PUBLIC>'
   aws ssm put-parameter --region us-east-1 --profile naro-cdk --name /naro/prod/push/vapid-private-key --type SecureString --value '<PRIVATE>'
   aws ssm put-parameter --region us-east-1 --profile naro-cdk --name /naro/prod/push/vapid-subject --type String --value 'https://naro.api.br'
   ```

   (Para não deixar a chave privada no histórico do shell, use
   `--value file:///dev/stdin` e cole o valor.)

5. **Novo deploy do backend** (ou reexecute o CodeDeploy) para o
   `configure_runtime.py` escrever as variáveis. O log do backend mostra
   `Web Push enabled`.

Web Push exige HTTPS no site (naro.api.br já é). Nada muda no Keycloak, nos
cookies de sessão ou no login.

## 8. Testar no Android

1. Chrome (ou Edge, Samsung Internet) no Android, em `https://naro.api.br`.
2. Opcional: instale (Menu → Instalar app). Funciona com ou sem instalação.
3. **Conversas → Ativar notificações** → permitir.
4. Feche o app ou bloqueie a tela; peça para um amigo mandar mensagem.
5. A notificação mostra nome e prévia (ou só o nome, em conversa E2EE), com o
   ícone "N" monocromático na barra de status. Tocar abre a conversa.
6. **Aviso no topo da tela (pop-up/heads-up):** o Chrome cria o canal de
   notificações de cada site com importância *padrão*: toca, vibra e aparece na
   barra, mas não salta no topo. Só no desktop o Chrome usa importância alta
   (`ChromeChannelDefinitions.isHighPrioritySiteNotificationsEnabled`), e o
   site não tem como mudar isso. A pessoa liga uma vez: toque e segure uma
   notificação do Naro → Configurações → ative "Pop-up na tela" (ou "Mostrar
   na tela"). O Naro mostra essa dica no Android ao ativar e em Configurações.
7. Se não chegar: Configurações do Android → Apps → Chrome → Notificações
   (e economia de bateria do fabricante, que às vezes atrasa o push).

## 9. Testar no iPhone/iPad (PWA)

A Apple só entrega Web Push para o **app instalado** (iOS/iPadOS 16.4+):

1. Safari → `https://naro.api.br` → Compartilhar → **Adicionar à Tela de Início**.
2. Abra o Naro **pelo ícone** e entre.
3. **Conversas → Ativar notificações** → Permitir. (No Safari fora do app, a
   linha em Configurações explica que é preciso instalar.)
4. Feche o app; peça uma mensagem. A notificação aparece como a de qualquer app,
   com o número no ícone quando o sistema permitir.
5. Ajustes → Notificações → Naro controla sons, banners e tela de bloqueio.

## 10. Testar no desktop

- **Chrome/Edge (Windows, macOS, Linux)**: ative no site ou no app instalado.
  O push chega com o navegador aberto, mesmo sem janela do Naro. No macOS,
  confira Ajustes do Sistema → Notificações → Google Chrome/Microsoft Edge.
- **Firefox**: igual; o push chega com o Firefox aberto.
- **Safari (macOS 13+)**: funciona no site e no app adicionado ao Dock.
- Com o Naro aberto numa aba visível e em foco, uma mensagem de outra conversa
  mostra só o toast e o contador; a conversa aberta não gera aviso nenhum.

## 11. Limitações dos navegadores

- **iOS/iPadOS**: só no app instalado (16.4+). Sem instalar, não há Web Push.
- **Permissão negada** não pode ser pedida de novo pelo site: a pessoa precisa
  liberar nas configurações do navegador/sistema. O Naro nunca pede permissão
  sozinho — só depois do toque em "Ativar".
- **Push sem notificação não existe**: Safari e Chrome exigem mostrar algo a
  cada push, por isso o service worker sempre mostra uma notificação (genérica
  se o payload vier estranho) e a supressão acontece no servidor.
- **Navegador fechado** (Chrome/Firefox no desktop): o push fica guardado pelo
  push service até 24 h (`naro.push.ttl`) e aparece quando o navegador abrir.
- **Badge no ícone** (`setAppBadge`): Chrome/Edge instalados, Safari/iOS
  instalados. Onde não existe, é ignorado.
- **Agrupamento**: uma notificação por conversa (a nova substitui a anterior e
  toca de novo). O iOS ignora `renotify` e pode agrupar do jeito dele.
- **Brave** exige ativar "Usar os serviços do Google para mensagens push".
- **Push services** aceitos: FCM (Chrome, Edge Android, Opera, Samsung),
  Mozilla, Apple e Windows (Edge). Um navegador com outro serviço recebe `400`
  ao ativar; inclua o host em `naro.push.allowed-endpoint-hosts` depois de
  conferir que é um push service legítimo.
- **Várias instâncias do backend**: o foco da conversa e a presença estão em
  memória. Com mais de uma instância, os dois precisam de um store compartilhado.

## Segurança e privacidade

- Todas as rotas exigem JWT. O dono de uma inscrição é sempre o usuário do
  token; `userId` no corpo é ignorado. Lista e remoção só enxergam as próprias
  inscrições (id de outra conta responde `404`, como inexistente).
- Um navegador pertence a uma conta por vez: se outra conta cadastrar o mesmo
  endpoint, ele passa a ser dela (novo id); o frontend também descarta, ao abrir,
  a inscrição que outra conta ativou naquele navegador.
- **Logout** apaga a inscrição deste navegador no servidor (antes de encerrar a
  sessão, melhor esforço, até 3 s): um computador compartilhado não continua
  recebendo as mensagens de quem saiu.
- **SSRF**: o servidor faz POST no endpoint recebido, por isso só aceita
  `https`, porta padrão e hosts de push services conhecidos (no cadastro e de
  novo antes de cada envio), sem seguir redirecionamentos.
- A chave privada VAPID nunca vai ao navegador, ao Git ou a logs (`toString`
  e mensagens de erro não a mostram). O endpoint também não vai a logs (é um
  segredo do navegador): os logs só têm host, status e ids.
- O conteúdo do push é cifrado para o navegador (RFC 8291): o push service
  não lê. Mensagens E2EE nunca têm prévia.
- `/user/queue/notifications` só leva metadados; o texto e o ciphertext
  continuam só em `/user/queue/chat`, aberto apenas na área do chat.
- O service worker trata o payload como não confiável: tipos e tamanhos
  conferidos, texto numa linha, ícone só do próprio site (URL de fora revelaria
  o IP de quem recebe) e o clique só navega para `/chat/<uuid>` deste site.
- As rotas do BFF que mudam estado conferem a `Origin`, além do cookie
  `SameSite=Lax`. O id da inscrição fica num cookie `HttpOnly` (`naro_push`),
  usado só no logout.

## API

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/v1/push/vapid-public-key` | `{ enabled, publicKey }` |
| `GET` | `/api/v1/push/subscriptions` | Aparelhos do usuário (sem endpoint nem chaves) |
| `POST` | `/api/v1/push/subscriptions` | Corpo = `PushSubscription.toJSON()` + `deviceName` opcional. `201` novo, `200` já existente |
| `DELETE` | `/api/v1/push/subscriptions/{id}` | `204`; `404` para id inexistente ou de outra conta |
| `GET` | `/api/v1/chats/unread` | `{ conversations, messages }` não lidas |
| `GET` | `/api/v1/chats` | Cada conversa ganhou `unreadCount` e `receipts` (campos novos; clientes antigos ignoram) |
| `POST` | `/api/v1/chats/{id}/delivered` | Corpo opcional `{ messageId }`. Sempre `204` (confirmação de entrega pelo service worker) |
| STOMP `SEND` | `/app/chat/focus` | `{ conversationId }` ou `{ conversationId: null }` |
| STOMP `SEND` | `/app/chat/delivered` | `{ conversationId, messageId }` ou `{}` (tudo) |
| STOMP `SUBSCRIBE` | `/user/queue/notifications` | `CHAT_MESSAGE` (metadados), `CONVERSATION_READ` e `RECEIPTS` |

BFF (Next): `GET /api/push/config`, `POST`/`DELETE /api/push/subscriptions`
(este aparelho), `GET /api/chats/unread`, `POST /api/chats/{id}/delivered`
(service worker; confere a `Origin`).

## Banco

- `V13__create_push_subscriptions.sql`: `push_subscriptions` (endpoint único,
  `ON DELETE CASCADE` com o usuário).
- `V14__create_conversation_reads.sql`: `conversation_reads` (FK composta para
  `conversation_participants`: só participante tem marcador) e o backfill
  `last_read_at = now()` para todas as participações existentes.
- `V15__add_conversation_delivery.sql`: `last_delivered_at` (backfill = leitura)
  e `last_read_at` passa a aceitar `NULL` (entregue, ainda não lida).

V13 e V14 só criam tabelas; V15 acrescenta uma coluna. Nenhuma migration antiga foi alterada. O Hibernate continua
em `ddl-auto: validate`.

## 12. Arquivos criados ou alterados

Backend (`naro`):

- Novos: `notification/**` (config, entidade, repositório, DTOs, serviços,
  controller, `webpush/*`), `chat/application/dto/{ChatFocusRequest,
  ChatNotificationResponse, UnreadChatsResponse}.java`,
  `chat/application/event/ConversationReadEvent.java`,
  `chat/application/service/ConversationFocusRegistry.java`,
  `chat/domain/repository/{ConversationUnreadCount, UnreadSummary}.java`,
  `chat/presentation/websocket/ChatFocusSessionListener.java`,
  `db/migration/V13__…`, `V14__…`, `scripts/generate-vapid-keys.sh`, este documento.
- Alterados: `ChatService(Impl)` (foco, leitura, não lidas),
  `ChatWebSocketController` (`/app/chat/focus`), `ChatRestController`
  (`/unread`), `ChatRealtimePublisher` (fila de avisos), `WebSocketSecurityConfig`
  (assinatura de `/user/queue/notifications`), `ConversationResponse`
  (`unreadCount`), `ChatMapper`, `ChatMessageRepository`,
  `ConversationRepository`, `application.yml`, `.gitleaks.toml` (vetor
  público do RFC 8291), `aws/scripts/{configure_runtime,runtime_support}.py`,
  `aws/src/main/java/com/naro/aws/NaroStack.java` (IAM dos parâmetros `/push/*`).
- Testes: `notification/**`, `ChatNotificationIntegrationTest`,
  `PushSubscriptionEndpointsIntegrationTest`, `NotificationMigrationTest`,
  `ConversationFocusRegistryTest`, casos novos em `ChatServiceImplTest`,
  `ChatWebSocketControllerTest`, `ChatRestControllerTest` e `test_runtime.py`.

Frontend (`naro_front`):

- Novos: `features/notification/**` (push, lógica pura, avisos em tela,
  provider, convite, linha de configurações, API do BFF), `app/api/push/**`,
  `app/api/chats/unread/route.ts`, `features/chat/{focus-reporter,
  use-conversation-focus}.ts`, `public/icons/badge-96.png`.
- Alterados: `public/sw.js` (push, clique, renovação de inscrição; o offline
  não mudou), `app/(app)/layout.tsx`, `app/(app)/settings/page.tsx`,
  `components/app-shell/{navigation.ts, sidebar.tsx, mobile-nav.tsx}` (badge
  de Conversas), `components/ui/toast.tsx` (ação opcional), `features/auth/actions.ts`
  (logout remove a inscrição), `features/chat/{schemas, conversation-list-state}.ts`,
  `features/chat/components/{chat-thread, conversation-list}.tsx`,
  `lib/api/{browser, route-handler}.ts`, `.env.example`.
