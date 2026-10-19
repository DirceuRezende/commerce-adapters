# Commerce Supplier Adapters — Spring Boot 4 / Java 25

Projeto de referência integrado de `MappingEngine` + `SupplierCommerceGateway` + adapters HTTP (REST/GraphQL) e MCP (Streamable HTTP via MCP Java SDK).

## Rodar

1. Requer JDK 25 e Maven 3.9+.
2. Configure as variáveis opcionais de ambiente `REST_B_URL`, `REST_B_TOKEN`, `GRAPHQL_C_URL`, `GRAPHQL_C_API_KEY`, `SHOPIFY_A_URL`, `SHOPIFY_A_TOKEN`.
3. Execute `mvn test` e `mvn spring-boot:run`.
4. Os endpoints `*.example.invalid` e operações como `create_cart` são placeholders: substitua pelos endpoints e ferramentas MCP anunciadas pelo parceiro (`tools/list`) e valide schemas reais.

As conexões ficam em `application.yml`, fora dos mappings de fornecedores. URLs relativas/paths definidos nos mappings **não** determinam host arbitrário. Para permitir destinos remotos, cada conexão deve estar explicitamente cadastrada em configuração confiável. O exemplo aceita HTTP apenas para localhost em testes; HTTPS é obrigatório em ambientes reais.

## Como chamar

```java
@Service
class MyService {
  private final SupplierCommerceGateway gateway;
  MyService(SupplierCommerceGateway gateway) { this.gateway = gateway; }

  JsonNode createCart(JsonNode cart) {
    var result = gateway.execute("rest-b", "createCart", cart);
    return result.response();
  }
}
```

Entrada canônica:

```json
{"items":[{"externalVariantId":"SKU-42","quantity":2}]}
```

Resposta REST simulada:

```json
{"cart_id":"C-99","status":"created"}
```

Saída canônica:

```json
{"externalCartId":"C-99","status":"created"}
```

## Módulos

O código principal tem 7 arquivos Java: aplicação, exceção comum, engine, gateway, configuração de conexões e dois adapters (HTTP e MCP). A API de uso do gateway e os contratos JSON dos fornecedores permanecem os mesmos.

Fluxo: `gateway.execute` → `MappingEngine.map(request)` → `switch (transport)` (`REST` → `HttpAdapter.rest`, `GRAPHQL` → `HttpAdapter.graphql`, `UCP_MCP` → `McpAdapter.call`) → `MappingEngine.map(response)`.

- `MappingEngine`: valida, compila e aplica regras de JsonPath, literais, transforms, arrays e objetos para request e response.
- `SupplierCommerceGateway`: carrega os JSONs de `suppliers/*.json` na inicialização, gerencia versões imutáveis (publicação e ativação), fixa uma versão para toda a operação, aplica os dois mappings e despacha pelo enum `Transport` da rota.
- `HttpAdapter`: JDK HttpClient com redirecionamentos desabilitados, autenticação bearer/api-key, limite de corpo de resposta 2 MB e erros com códigos estruturados. `rest`: GET com query primitives; POST/PUT/PATCH/DELETE em JSON; HTTP status explícito. `graphql`: POST com `query`/`variables`, falha quando houver `errors`, retorna somente `data`.
- `McpAdapter`: gerencia clientes MCP por conexão/path, inicializa no primeiro uso e fecha na finalização; executa `tools/call` pelo SDK, prioriza `structuredContent`, aceita fallback para texto JSON e rejeita `isError`.
- `PartnerConnections`: allowlist de conexões nomeadas, segredos fora do mapping e timeout por parceiro.
- `CommerceException`: erro comum de mapping e integração, com código (`MAPPING_ERROR` para mappings), status HTTP opcional e causa.
- `AdaptersIntegrationTest`: usa `HttpServer` local e mock do SDK MCP, sem APIs externas.

## Segurança e limites

- As regras de mapping devem ser publicadas somente por administradores. O registry **não** permite que entradas de usuários escolham hosts; conexão remota deve ser cadastrada em configuração de aplicação confiável.
- Não há retry automático para POST, checkout, pagamento, mutation ou `tools/call`: em caso de timeout, a operação pode ter sido executada remotamente. A idempotência deve ser propagada pelo orquestrador conforme o contrato de cada fornecedor.
- O `McpAdapter` é *application scoped*; não reutilize o mesmo cliente entre compradores com tokens/capacidades por usuário. Para OAuth com tokens dinâmicos e multi-tenancy, adapte o gerenciamento de clientes MCP para isolar sessões por identidade e dar suporte a refresh e invalidation.
- Este registry é somente em memória. Em Kubernetes/múltiplas instâncias, use DB de versões e distribuição de mudança de versão; não aplique hot reload sem coordenação.
- `structuredContent` pode não ser fornecido pelo servidor; fallback considera apenas textos contendo objeto JSON válido. Resultados não-JSON geram falha explícita.
- Os `responseMapping`s de exemplos pressupõem um determinado contrato externo e precisam ser adaptados às respostas de cada fornecedor.
- As operações de finalizar checkout/pagamento e estado dos pedidos não estão incluídas: pertencem ao orquestrador, com idempotência, audit trail, lock e Outbox.
- Antes de produção: adicionar JSON Schema, políticas de nulidade explícitas, métricas/tracing, políticas DNS/egress contra SSRF, rotação de credenciais, cancelamento/refresh de sessão MCP, descoberta e validação de ferramentas/capabilities, e limitação de concorrência por parceiro.

## Validação

Execute `mvn test` com JDK 25 e Maven 3.9+. A suíte cobre transformações, inicialização com os três fornecedores, versionamento durante chamadas, seleção de adapters, ciclo de vida dos clientes MCP e integrações REST/GraphQL com servidor local e MCP com mock do SDK.

Os testes HTTP precisam de permissão para abrir sockets locais. Não acessam APIs externas.
