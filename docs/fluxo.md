# Fluxo: classes de mapping e integração

O pacote `mapping` decide **o quê** fazer e **como traduzir** os dados. O pacote `integration` decide **como falar** com o parceiro.

```
Controller → SupplierCommerceGateway → MappingEngine (request)
                    │
                    ├─ REST/GRAPHQL → HttpAdapter ─┐
                    └─ UCP_MCP      → McpAdapter  ─┴→ PartnerConnections (host, token, timeout)
                    │
             MappingEngine (response) → GatewayResult
```

---

## `mapping/MappingEngine`

Traduz JSON para JSON a partir de regras declarativas. Não sabe nada de supplier nem de protocolo.

Ele trabalha em duas fases.

**1. `compile(JsonNode rules)`** valida as regras uma vez e as transforma em `Map<String, Rule>` imutável. Essa fase roda no `publish`, nunca por request. Ela verifica:
- Cada campo tem `path` (JsonPath começando com `$`) **ou** `literal`, nunca os dois.
- `required` é booleano.
- `fields` e `each` não aparecem juntos.
- Toda transform está entre `trim`, `uppercase`, `greaterThan` e `decimalToMinor`.
- A profundidade não passa de 20.

**2. `map(JsonNode source, Map<String, Rule>)`** aplica as regras compiladas e devolve um `ObjectNode`. Para cada campo:
- Lê o valor do `literal` ou do JsonPath. Se o valor não existe e o campo é `required`, falha; se é opcional, o campo é omitido.
- Com `each`, exige um array (no máximo 10.000 itens) e aplica o sub-mapping em cada item.
- Com `fields`, exige um objeto e aplica o sub-mapping nele.
- Aplica as `transforms` em ordem. Exemplo: `decimalToMinor` converte `49.90` BRL em `4990`, usando a escala da moeda.

O mesmo motor serve para o request (canônico → parceiro) e para o response (parceiro → canônico).

Records internos: `Rule(path, literal, required, transforms, fields, each)` e `Transform(name, args)`.

---

## `mapping/SupplierCommerceGateway`

É o ponto de entrada do fluxo e acumula dois papéis.

**Papel 1: registro de versões dos suppliers.**
- `run()`: no startup, carrega cada `suppliers/*.json` e chama `publish` e `activate`.
- `publish(json)`: valida o documento (`supplierId`, `version`, rotas, path seguro, transporte válido via `Transport.valueOf`) e compila os mappings com o `MappingEngine`. Só grava se tudo passar. Uma versão já publicada não pode ser sobrescrita.
- `activate(supplierId, version)`: aponta o supplier para uma versão já publicada.
- `active(...)` e `version(...)`: fazem as consultas.
- O estado fica em dois `ConcurrentHashMap`: versões por supplier e versão ativa por supplier.

**Papel 2: execução.**
- `execute(supplier, operation, input)` usa a versão ativa. `executeVersion(...)` usa uma versão específica.
- `executePinned` pega o `SupplierVersion` **uma vez** e usa esse mesmo objeto imutável do começo ao fim:
  1. Aplica o request mapping.
  2. Faz um `switch` no `Transport`: `REST` → `http.rest`, `GRAPHQL` → `http.graphql`, `UCP_MCP` → `mcp.call`.
  3. Aplica o response mapping.
  4. Devolve um `GatewayResult(supplierId, mappingVersion, operation, response)`.

Por isso, se outra versão for ativada no meio de uma chamada, o request e o response continuam usando o mesmo contrato.

**Templates de plataforma.** No construtor, o gateway carrega `platforms/*.json` (hoje só `shopify.json`). Cada template traz as operações e os mappings padrão de uma plataforma, com rota sem `connection`. Um supplier dessa plataforma declara apenas:

```json
{"supplierId": "hubii", "version": 1, "platform": "shopify", "connection": "hubii"}
```

No `publish`, `withPlatform` expande esse documento antes de compilar:
- Copia as operações do template e preenche `route.connection` com a conexão do supplier.
- Se o supplier tiver `operations`, cada uma sobrescreve os campos da operação de mesmo nome (`route`, `requestMapping` ou `responseMapping`, campo a campo). Uma operação que não existe no template é adicionada.
- Plataforma desconhecida ou `connection` ausente falham com `CommerceException`.

Depois da expansão, a validação e o versionamento são os mesmos de um supplier escrito por extenso.

**Records internos:**
- `Transport` (enum).
- `Route(transport, connection, path, action)`: para onde e como chamar.
- `Operation(route, request, response)`: a rota e os dois mappings compilados.
- `SupplierVersion(supplierId, version, operations)`.
- `GatewayResult`.

---

## `integration/PartnerConnections`

Guarda a allowlist de destinos. É um `@ConfigurationProperties(prefix = "commerce")` que lê `commerce.connections.*` do `application.yml`.

- `Connection` tem `baseUrl`, `bearerToken`, `apiKey`, `apiKeyHeader` (padrão `X-API-Key`) e `timeout` (padrão 10s).
- `lookup(route)` busca a conexão pelo **nome** em `route.connection()`. Se não encontrar, falha com `CONNECTION_UNKNOWN`. Também valida a URL e o timeout.
- `validateBase` só aceita uma origem pura: sem path, query, fragment ou user-info, e sem `/` no final. Exige HTTPS, exceto em `localhost`/`127.0.0.1`.
- `uri(route)` monta `baseUrl + route.path()`.

Assim o JSON do supplier nunca escolhe o host nem carrega credenciais. Ele só referencia um nome que alguém cadastrou na configuração.

---

## `integration/HttpAdapter`

Faz chamadas HTTP com JSON usando o `HttpClient` do JDK. Os timeouts são 3s para conectar e o `timeout` da conexão para cada request. Redirects ficam desligados.

- **`rest(route, payload)`**: o verbo vem de `route.action()`.
  - Em `GET`, o payload vira query string. Só valores primitivos são aceitos; `null` é omitido e objeto ou array gera `INVALID_REQUEST`.
  - Em `POST`, `PUT` e `PATCH`, o payload vai como corpo JSON.
- **`graphql(route, payload)`**: exige `query` textual e faz `POST`. Qualquer `errors` falha a chamada (`GRAPHQL_ERRORS`), mesmo com sucesso parcial. Sem `data`, falha com `GRAPHQL_NO_DATA`. Devolve **só** o objeto `data`, então o response mapping usa `$.createCart...` em vez de `$.data.createCart...`.
- **`execute(...)`** (privado) é o núcleo comum:
  - Aplica a autenticação.
  - Lê a resposta com limite de 2 MB (`BODY_TOO_LARGE`).
  - Rejeita status fora de 2xx (`HTTP_ERROR`, com `httpStatus`), corpo vazio e JSON inválido.
  - Converte erros de I/O em `NETWORK_ERROR`.
- **`applyAuth(...)`** (estático): envia `Authorization: Bearer` e/ou o header de API key. Recusa nomes de header perigosos, como `Host` e `Authorization`. O `McpAdapter` também usa esse método.

Nenhuma mensagem de erro inclui o corpo da resposta do parceiro nem credenciais.

---

## `integration/McpAdapter`

Faz chamadas MCP pelo SDK Java (Streamable HTTP).

- **`call(route, arguments)`**: envia `tools/call` com `name = route.action()` e `arguments` iguais ao payload mapeado, que precisa ser um objeto. Na resposta:
  - `isError` vira `MCP_TOOL_ERROR`.
  - Usa o `structuredContent` se ele vier.
  - Se não vier, usa o primeiro bloco de texto que contenha um objeto JSON.
  - Se nada servir, falha com `MCP_NO_STRUCTURED_JSON`.
  - Outros erros do SDK, como o `UCP discovery failed` da Shopify, viram `MCP_CALL_FAILED`.
- **`client(route)`**: guarda as sessões em cache por `connection|path`. A primeira chamada cria a sessão; as seguintes reutilizam.
- **`createClient(...)`**: monta o transport com `baseUrl + path`, autenticação, limite de 2 MB e timeout, e chama `initialize()` para negociar protocolo e capacidades. Se o `initialize()` falhar, fecha o cliente e lança `MCP_INIT_FAILED`.
- **`close()`** (`@PreDestroy`): fecha todas as sessões quando a aplicação para.

A sessão é por aplicação, não por usuário. Ela serve para credenciais fixas por parceiro. Com tokens OAuth por comprador, seria preciso isolar uma sessão por identidade.

---

## Resumo de responsabilidades

| Classe | Sabe sobre | Não sabe sobre |
| --- | --- | --- |
| `MappingEngine` | JsonPath, regras, transforms | suppliers, protocolos, rede |
| `SupplierCommerceGateway` | versões, operações, qual transporte usar | detalhes de HTTP/MCP, credenciais |
| `PartnerConnections` | hosts permitidos, tokens, timeouts | mappings, operações |
| `HttpAdapter` | HTTP, REST, GraphQL, limites e erros | mappings, versões |
| `McpAdapter` | sessões MCP, `tools/call` | mappings, versões |

Todas as falhas viram `CommerceException(code, httpStatus, message)`. O `CatalogController` traduz essas exceções em respostas HTTP: 400 para `MAPPING_ERROR` e 502 para o resto.
