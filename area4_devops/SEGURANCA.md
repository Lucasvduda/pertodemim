# Segurança e privacidade

## Controles implementados (cada um tem teste, ver `PLANO_DE_TESTES.md`)

| Controle | Onde | Teste |
|---|---|---|
| Autenticação JWT sem estado; senha com BCrypt (nunca em texto puro) | `security/`, `AuthController` | `ApiIntegrationTest`, `JwtServiceTest` |
| Mesma mensagem e mesmo tempo de resposta para e-mail inexistente e senha errada (evita enumeração de contas) | `AuthController` | `ApiIntegrationTest` |
| Todas as rotas `/api/**` exigem token, exceto login, cadastro e ingestão IoT | `SecurityConfig` | `ApiIntegrationTest` |
| Ingestão IoT autenticada por chave de dispositivo (uma por unidade e tipo), só o hash SHA-256 fica no banco; 401 antes de ler o corpo | `DeviceKeyHeaderFilter`, `IotKeys` | `IotControllerTest`, `IotKeysTest` |
| Limite de requisições (429 com `Retry-After`) em login e triagem | `RateLimitFilter` | `RateLimitTest` |
| Validação de entrada em todo endpoint (400) e erros padronizados `{"erro","campos"}` sem stack trace | `ApiExceptionHandler` | `ApiIntegrationTest` |
| Cabeçalhos CSP, Referrer-Policy e X-Frame-Options; apenas `/actuator/health` exposto | `SecurityConfig` | `ApiIntegrationTest` |
| Front sem `innerHTML`, sem scripts de terceiros (CSP restritiva) | `static/` | `FrontEstruturaTest` |
| Entrada do LLM anonimizada; saída do LLM tratada como não confiável (lista fechada, texto do app, piso de urgência) | `Anonimizador`, `RespostaLlmParser` | `AnonimizadorTest`, `RespostaLlmParserTest` |
| Sinais de emergência não passam pelo LLM | `RedFlagDetector` | `RedFlagDetectorTest` |
| Injeção SQL no texto livre entra como texto comum (JPA parametrizado) | repositórios | `ApiIntegrationTest` |
| Consentimento LGPD no cadastro e na triagem; só o texto anonimizado é guardado | `AuthController`, `TriagemRequest` | `ApiIntegrationTest` |
| Segredos só por variável de ambiente; `.env` fora do git e do `.zip`; varredura de padrões de chave nos testes | `.gitignore`, `entrega/montar_entrega.py` | `area4_devops/tests/test_estrutura.py` |
| Contêineres sem root (UID 10001) | Dockerfiles | `test_estrutura.py` (leitura do arquivo; imagem não construída aqui) |

## Limites conhecidos (não esconder)

- O segredo JWT e a semente das chaves IoT têm valor padrão **só para desenvolvimento**. Em uso real, defina `JWT_SECRET` (32+ caracteres) e `IOT_KEY_SEED`.
- Limite de requisições em memória e por instância; não protege contra ataque distribuído.
- Sem renovação de token, sem revogação e sem verificação de e-mail.
- O front guarda o token em `sessionStorage` (some ao fechar a aba); um XSS ainda poderia lê-lo. A CSP e a ausência de `innerHTML` reduzem o risco, não o eliminam.
- Não houve teste de penetração nem análise de dependências (CVE) automatizada além do que o Maven e o GitHub mostrarem.
- Dados pessoais: nome e e-mail do cadastro ficam no banco H2 em memória (apagados ao reiniciar). A política de retenção para um banco persistente não foi definida.

## Como relatar um problema

Abra uma issue privada no repositório do grupo ou fale com os integrantes listados no README.
