# BG3 Build Planner — Documentação

Sistema de busca de _builds_ para Baldur's Gate 3, modelado como um problema de grafo. Permite duas funcionalidades principais:

1. **Busca por features**: dado um conjunto de features desejadas, encontra a menor combinação de classes/níveis que dá acesso a todas elas.
2. **Busca por tags**: dado um conjunto de tags e um limite de níveis, encontra a build que cobre mais features daquelas tags.

---

## Estrutura de Pastas

```
bg3-builder/
├── pom.xml                              # Configuração do Maven (Java 21 + Jackson 2.17.0)
│
├── data/                                # Arquivos JSON com dados do BG3
│   ├── features.json                    # ~800 features (spells, feats, class features, proficiências)
│   ├── choice_lists.json                # ~55 pools (listas de opções selecionáveis)
│   ├── classes.json                     # 12 classes base com progressão completa de níveis 1-12
│   └── subclasses.json                  # ~65 subclasses (incluindo 8 variantes de Circle of the Land)
│
└── src/main/java/bg3builder/
    │
    ├── Main.java                        # Ponto de entrada, demonstra uso da API
    │
    ├── model/                           # Records imutáveis que representam dados em memória
    │   ├── Feature.java                 # Uma feature (id, nome, tipo, tags, classes, etc.)
    │   ├── ChoiceList.java              # Wrapper sobre o mapa de pools
    │   ├── ClassProgression.java        # Progressão de uma classe (níveis + bonus inicial)
    │   ├── SubclassProgression.java     # Progressão de uma subclasse (parent_class + níveis)
    │   └── Build.java                   # Uma build = mapa classe→nível + classe inicial + subclasses
    │
    ├── data/                            # Carregamento de dados e índices pré-computados
    │   ├── DataLoader.java              # Lê os 4 arquivos JSON do disco
    │   └── Indexes.java                 # Índices de busca rápida (feature→fontes, tag→features)
    │
    └── search/                          # Algoritmos de busca e API
        ├── BuildExpander.java           # Calcula quais features uma build alcança
        ├── BuildPlan.java               # Estrutura de saída (plano nível-a-nível)
        ├── BuildMaterializer.java       # Converte Build em BuildPlan detalhado
        ├── FeatureSearch.java           # Algoritmo 1: busca por features (A* com heurística)
        ├── TagSearch.java               # Algoritmo 2: busca por tags (poda top-K classes)
        └── BuildAPI.java                # Interface JSON-in/JSON-out de alto nível
```

---

## Visão Geral dos Arquivos

### Arquivos de Dados

#### `features.json`

Catálogo de todas as features do jogo. Cada entrada tem este formato:

```json
"fireball": {
  "name": "Fireball",
  "type": "spell",
  "spell_level": 3,
  "school": "evocation",
  "tags": ["damage", "fire", "area"],
  "classes": ["sorcerer", "wizard"]
}
```

Tipos possíveis: `spell`, `feat`, `class_feature`, `proficiency`, `fighting_style`. Apenas `spell` tem `spell_level`, `school` e `classes`. Tags são usadas pela busca por tags.

#### `choice_lists.json`

Pools nomeados de IDs de features. Quando uma classe oferece "escolha 2 cantrips do spell list de Wizard", o JSON da classe referencia um pool aqui. Exemplo:

```json
"spells_wizard": ["acid_splash", "fire_bolt", "fireball", ...],
"feats": ["actor", "alert", "ability_improvement_strength_2", ...],
"barbarian_subclasses": ["berserker", "giant", "wild_magic", "wildheart"]
```

#### `classes.json`

Progressão das 12 classes base. Cada classe tem 12 níveis e um bloco `starting_class_bonus` aplicado apenas quando ela é a classe inicial da build. Exemplo de um nível:

```json
"5": {
  "grants": ["extra_attack"],
  "choices": [
    {"pick": 2, "from_list": "spells_wizard", "filter": {"spell_level": 3}}
  ]
}
```

#### `subclasses.json`

Mesma forma que `classes.json` mas com `parent_class` e `available_at_level`. Os níveis das subclasses são **absolutos** (nível 3 da subclasse = nível 3 da classe pai).

---

## Arquivos de Código — Modelos

### `Feature.java`

```java
public record Feature(
    String id, String name, String type,
    List<String> tags, List<String> classes,
    Integer spellLevel, String school
)
```

Record imutável. Não tem métodos públicos — apenas armazena dados de uma feature.

### `ChoiceList.java`

Wrapper sobre `Map<String, List<String>>`. Métodos:

| Método | Entrada | Saída |
|---|---|---|
| `get(poolName)` | `String` (nome do pool) | `List<String>` (IDs das features no pool, ou lista vazia se não existir) |
| `has(poolName)` | `String` | `boolean` |
| `all()` | — | `Map<String, List<String>>` (todos os pools) |

### `ClassProgression.java`

Record com 4 campos: `id`, `name`, `levels` (mapa "1".."12" → `LevelEntry`), `startingClassBonus`. Records aninhados:

- `LevelEntry`: `grants` (lista de IDs concedidos diretamente), `grantsPool` (pools concedidos por inteiro), `choices` (escolhas que o usuário faz).
- `StartingClassBonus`: `grants` + `choices` aplicados só quando esta é a classe inicial.
- `Choice`: `pick` (quantos), `fromList` (nome do pool), `filter` (ex: `{"spell_level": 3}`).
- `PoolReference`: igual a `Choice` mas sem `pick` (pool concedido por inteiro).

| Método | Entrada | Saída |
|---|---|---|
| `levelEntry(level)` | `int` | `LevelEntry` (ou `null` se nível não existir) |

### `SubclassProgression.java`

Mesma forma que `ClassProgression` mas adiciona `parentClass` e `availableAtLevel`. O DTO interno é usado pelo Jackson para deserializar o JSON.

### `Build.java`

Representa uma build de personagem.

```java
public record Build(
    Map<String, Integer> classLevels,    // ex: {"wizard": 5, "fighter": 2}
    String startingClass,                 // ex: "wizard"
    Map<String, String> subclasses        // ex: {"wizard": "evocation_school"}
)
```

| Método | Entrada | Saída |
|---|---|---|
| `totalLevels()` | — | `int` (soma de todos os níveis) |
| `Build.singleClass(classId, levels)` | `String, int` | `Build` (build de classe única) |
| `Build.Builder` | (fluente) | Construtor mutável que ao final retorna `Build` imutável |

---

## Arquivos de Código — Carregamento e Índices

### `DataLoader.java`

Lê os 4 arquivos JSON do disco e retorna estruturas em memória.

| Método | Entrada | Saída |
|---|---|---|
| `loadFeatures(path)` | `File` | `Map<String, Feature>` (id → feature) |
| `loadChoiceLists(path)` | `File` | `ChoiceList` |
| `loadClasses(path)` | `File` | `Map<String, ClassProgression>` |
| `loadSubclasses(path)` | `File` | `Map<String, SubclassProgression>` |

Todos os métodos ignoram chaves que começam com `_` (usadas para comentários nos arquivos JSON). Para classes e subclasses usa o truque de re-serializar cada entrada como JSON e deixar o Jackson fazer o binding no DTO interno, depois converter para o record principal.

### `Indexes.java`

Construído uma vez no startup a partir dos dados carregados. Gera dois índices que aceleram as buscas:

1. **featureSources**: feature ID → lista de slots (classe, subclasse, nível, origem) que a concedem.
2. **tagIndex**: tag → lista de feature IDs que carregam aquela tag.

Tipo aninhado:

```java
public record Source(String classId, String subclassId, int level, Origin origin)
```

`Origin` é um enum com 8 valores distinguindo concessões diretas, pools, escolhas, bonus inicial e variantes de subclasse.

| Método | Entrada | Saída |
|---|---|---|
| `Indexes.build(features, classes, subclasses, pools)` | maps + ChoiceList | `Indexes` |
| `sourcesOf(featureId)` | `String` | `List<Source>` |
| `featuresWithTag(tag)` | `String` | `List<String>` |
| `classesThatSource(featureId)` | `String` | `Set<String>` (classes únicas que a concedem) |
| `minLevelForFeatureInClass(featureId, classId)` | `String, String` | `int` (menor nível ou `MAX_VALUE` se não concedida) |
| `allClassIds()` | — | `Set<String>` |
| `orphanFeatures(features)` | `Map<String, Feature>` | `Set<String>` (features sem nenhuma fonte) |
| `featuresWithSources()` | — | `int` |
| `distinctTags()` | — | `int` |

A função `orphanFeatures` é diagnóstica — features órfãs geralmente indicam um typo num ID ou uma feature não referenciada por nenhuma classe.

---

## Arquivos de Código — Busca

### `BuildExpander.java`

Núcleo do sistema. Calcula quais features uma build "alcança" (no sentido de capacidade — ou seja, qualquer opção em qualquer escolha conta como alcançável, não importa se o usuário escolheu ela ou não).

Aplica:
1. Todos os `grants` dos níveis tomados.
2. Todos os `grants_pool` expandidos contra `features.json` (com filtros de spell_level).
3. Todas as opções dos `choices` (capacidade — todas alcançáveis).
4. Bonus de classe inicial se aplicável.
5. Para cada classe da build, **todas** as subclasses com aquele `parent_class` (capacidade entre subclasses — todas contribuem).

| Método | Entrada | Saída |
|---|---|---|
| `reachableFeatures(build)` | `Build` | `Set<String>` (IDs de todas as features alcançáveis) |

### `BuildPlan.java`

Estrutura de saída do sistema. Records aninhados que serializam para o JSON de resposta.

```java
public record BuildPlan(
    boolean found,
    int totalLevels,
    String startingClass,
    Map<String, Integer> classLevels,
    Map<String, String> subclassPicks,
    List<LevelStep> progression,         // detalhe nível-a-nível
    List<String> targets,
    List<String> targetsSatisfied,
    List<String> targetsUnsatisfied,
    long searchTimeMs,
    long nodesExplored
)
```

`LevelStep` representa um nível de personagem (1..total). Inclui:
- `level`: o nível do personagem (1..12)
- `classId`, `classLevel`: classe tomada e nível dentro dela
- `starting`: se é o nível 1 da classe inicial
- `subclassPick`: se este nível dispara escolha de subclasse, qual foi escolhida
- `grants`: features concedidas automaticamente (com flag `targetMatch` indicando se bate com algum alvo)
- `choices`: escolhas significativas que o usuário precisa fazer (apenas escolhas que tocam um alvo ou são pick de subclasse)

### `BuildMaterializer.java`

Converte um `Build` (apenas multiset de classe-nível) em um `BuildPlan` detalhado.

Decisões importantes:
- **Ordem dos níveis**: classe inicial primeiro (até esgotar), depois outras classes em ordem alfabética. O usuário pode reorganizar no jogo.
- **Escolha de subclasse**: para cada classe, escolhe a subclasse que concede mais features-alvo. Empate desempata em ordem alfabética.
- **Escolhas significativas**: uma escolha é incluída se (a) alguma opção é um alvo do usuário OU (b) é um pick de subclasse. Escolhas tipo "escolha qualquer feat" são omitidas.

| Método | Entrada | Saída |
|---|---|---|
| `materialize(build, targets, reachable, searchTimeMs, nodesExplored)` | `Build, Set<String>, Set<String>, long, long` | `BuildPlan` |

Se `build` for `null` (nenhuma build encontrada), retorna um `BuildPlan` com `found: false`.

### `FeatureSearch.java`

**Algoritmo 1**: busca a menor build que alcança um conjunto de features-alvo.

Implementa **A\* (A estrela)** com heurística admissível. Cada nó é uma build parcial. O custo é a quantidade total de níveis. A heurística é um limite inferior nos níveis adicionais necessários.

**Heurística admissível**: para cada alvo não satisfeito T, calcula o mínimo de níveis adicionais necessários em alguma classe que conceda T (considerando os níveis já investidos). O máximo entre todos os alvos não satisfeitos é a heurística — admissível porque cada alvo precisa ser pago individualmente.

**Podas aplicadas**:
1. **Filtro de classes relevantes (dinâmico)**: a cada nó, só expande para classes que concedem pelo menos um alvo **ainda não satisfeito**.
2. **Forma canônica (visited)**: builds com mesma composição e mesma classe inicial são consideradas iguais — evita visitar a mesma build em ordens diferentes.
3. **Poda h**: se `g(n) + h(n) > 12` (cap do BG3), o nó não pode levar a solução dentro do orçamento. Descarta.

| Método | Entrada | Saída |
|---|---|---|
| `findSmallestBuild(targetFeatures)` | `Set<String>` | `Build` (ou `null` se impossível) |
| `lastNodesExplored()` | — | `long` (estatística da última busca) |
| `FeatureSearch.describe(build)` | `Build` | `String` (formato legível) |

### `TagSearch.java`

**Algoritmo 2**: busca a build que cobre mais features tageadas, dentro de um limite de níveis.

Algoritmo:
1. **Conjunto candidato**: união de features-com-tag-alvo via `tagIndex`.
2. **Top-K classes**: para cada uma das 12 classes, calcula quantas features candidatas ela alcança no nível máximo. Ordena descendente, pega top K (default = 4).
3. **Enumeração**: enumera todas as composições de 1..K classes que somam ≤ levelCap, com cada classe possível como classe inicial.
4. **Score**: para cada build enumerada, calcula `|reachable ∩ candidatos|`. Ordena por (score DESC, totalLevels ASC).
5. **Retorna top N**.

**Por que top-K funciona**: o número de composições de 12 classes somando ≤ N cresce ~C(N+11, 11). Em cap 6 isso são ~74000 builds. Restringir para top 4 classes reduz para ~336 builds — speedup de ~200×. Trade-off aceitável: classes que não ajudam individualmente raramente ajudam em combinação.

```java
public record ScoredBuild(Build build, int score, Set<String> matchedFeatures)
```

| Método | Entrada | Saída |
|---|---|---|
| `findBestBuilds(targetTags, levelCap, topN)` | `Set<String>, int, int` | `List<ScoredBuild>` |
| `findBestBuilds(targetTags, levelCap, topN, topK)` | `Set<String>, int, int, int` | `List<ScoredBuild>` (controla K manualmente) |
| `lastBuildsEvaluated()` | — | `long` |

### `BuildAPI.java`

Camada de mais alto nível — recebe JSON, devolve JSON. Esta é a interface que um frontend ou CLI usaria.

| Método | Entrada (JSON) | Saída (JSON) |
|---|---|---|
| `findBuildForFeatures(jsonInput)` | `{"features": [...]}` | `BuildPlan` serializado |
| `findBuildForFeatures(features)` | `List<String>` | `BuildPlan` serializado |
| `findBuildsForTags(jsonInput)` | `{"tags": [...], "max_levels": N, "top_n": M}` | `TagSearchResponse` serializado |
| `findBuildsForTags(tags, maxLevels, topN)` | `List<String>, int, int` | `TagSearchResponse` serializado |

O Jackson é configurado com `PropertyNamingStrategies.SNAKE_CASE`, então campos Java em camelCase viram snake_case no JSON (ex: `totalLevels` → `total_levels`).

---

## Interface da API — Formato dos Dados

### Função 1: Busca por Features

**Entrada (JSON)**:

```json
{
  "features": ["fireball", "extra_attack", "action_surge"]
}
```

- `features`: lista de IDs de features. Devem existir em `features.json`.

**Saída (JSON)**:

```json
{
  "found": true,
  "total_levels": 10,
  "starting_class": "sorcerer",
  "class_levels": {
    "fighter": 5,
    "sorcerer": 5
  },
  "subclass_picks": {
    "fighter": "champion",
    "sorcerer": "draconic_bloodline"
  },
  "progression": [
    {
      "level": 1,
      "class_id": "sorcerer",
      "class_level": 1,
      "starting": true,
      "subclass_pick": "draconic_bloodline",
      "grants": [
        {
          "id": "dagger_proficiency",
          "name": "Dagger Proficiency",
          "target_match": false,
          "from_subclass": null
        },
        {
          "id": "draconic_resilience",
          "name": "Draconic Resilience",
          "target_match": false,
          "from_subclass": "draconic_bloodline"
        }
      ],
      "choices": [
        {
          "from_pool": "sorcerer_subclasses",
          "filter": {},
          "pick_count": 1,
          "recommended": ["draconic_bloodline"],
          "reason": "subclass pick"
        }
      ]
    },
    {
      "level": 5,
      "class_id": "sorcerer",
      "class_level": 5,
      "starting": false,
      "subclass_pick": null,
      "grants": [],
      "choices": [
        {
          "from_pool": "spells_sorcerer",
          "filter": { "spell_level": 3 },
          "pick_count": 1,
          "recommended": ["fireball"],
          "reason": "matches target(s)"
        }
      ]
    }
  ],
  "targets": ["fireball", "extra_attack", "action_surge"],
  "targets_satisfied": ["fireball", "extra_attack", "action_surge"],
  "targets_unsatisfied": [],
  "search_time_ms": 28,
  "nodes_explored": 142
}
```

**Campos da resposta**:

| Campo | Tipo | Descrição |
|---|---|---|
| `found` | bool | `true` se uma build foi encontrada, `false` caso contrário |
| `total_levels` | int | Soma dos níveis (sempre ≤ 12) |
| `starting_class` | string | ID da classe inicial |
| `class_levels` | obj | Mapa classe → níveis tomados |
| `subclass_picks` | obj | Mapa classe → subclasse recomendada |
| `progression` | array | Lista de objetos `LevelStep`, um por nível do personagem |
| `targets` | array | Os alvos pedidos pelo usuário |
| `targets_satisfied` | array | Alvos que a build alcança |
| `targets_unsatisfied` | array | Alvos não alcançados (vazio se sucesso) |
| `search_time_ms` | int | Tempo de busca em milissegundos |
| `nodes_explored` | int | Quantos nós o A* explorou |

**Estrutura de um `LevelStep`** (item da `progression`):

| Campo | Tipo | Descrição |
|---|---|---|
| `level` | int | Nível do personagem (1..12) |
| `class_id` | string | Classe tomada neste nível |
| `class_level` | int | Nível dentro da classe |
| `starting` | bool | `true` se for o nível 1 da classe inicial |
| `subclass_pick` | string ou null | Se este nível dispara escolha de subclasse, qual foi |
| `grants` | array de `GrantedFeature` | Features concedidas automaticamente |
| `choices` | array de `MeaningfulChoice` | Escolhas significativas |

**`GrantedFeature`**:

| Campo | Tipo | Descrição |
|---|---|---|
| `id` | string | ID da feature |
| `name` | string | Nome legível |
| `target_match` | bool | `true` se esta feature é um dos alvos do usuário |
| `from_subclass` | string ou null | ID da subclasse se veio de uma; null se da classe base |

**`MeaningfulChoice`**:

| Campo | Tipo | Descrição |
|---|---|---|
| `from_pool` | string | Nome do pool de onde escolher |
| `filter` | obj | Filtro aplicado (ex: `{"spell_level": 3}`) |
| `pick_count` | int | Quantas features escolher |
| `recommended` | array | IDs recomendados (alvos que estão no pool, ou subclasse escolhida) |
| `reason` | string | "matches target(s)" ou "subclass pick" |

### Função 2: Busca por Tags

**Entrada (JSON)**:

```json
{
  "tags": ["fire", "damage"],
  "max_levels": 5,
  "top_n": 3
}
```

- `tags`: lista de tags desejadas. Build deve maximizar quantas features com essas tags são alcançáveis.
- `max_levels`: limite máximo de níveis totais (1..12). Default 5 se ausente.
- `top_n`: quantos resultados retornar. Default 3 se ausente.

**Saída (JSON)**:

```json
{
  "tags": ["fire", "damage"],
  "max_levels": 5,
  "search_time_ms": 47,
  "builds_evaluated": 336,
  "results": [
    {
      "tags_covered": 42,
      "plan": {
        "found": true,
        "total_levels": 5,
        "starting_class": "wizard",
        "class_levels": { "wizard": 5 },
        "subclass_picks": { "wizard": "evocation_school" },
        "progression": [...],
        "targets": [...],
        "targets_satisfied": [...],
        "targets_unsatisfied": [],
        "search_time_ms": 47,
        "nodes_explored": 336
      }
    },
    {
      "tags_covered": 41,
      "plan": { ... }
    }
  ]
}
```

**Campos da resposta**:

| Campo | Tipo | Descrição |
|---|---|---|
| `tags` | array | Tags pedidas (ecoadas de volta) |
| `max_levels` | int | Cap pedido (ecoado) |
| `search_time_ms` | int | Tempo total de busca |
| `builds_evaluated` | int | Quantas builds foram pontuadas |
| `results` | array | Top N builds, ordenadas por `tags_covered` DESC |

**Cada item de `results`**:

| Campo | Tipo | Descrição |
|---|---|---|
| `tags_covered` | int | Quantas features tageadas a build alcança |
| `plan` | obj | Mesmo formato `BuildPlan` da Função 1 |

No `plan`, o array `targets` lista as features tageadas que a build efetivamente alcança (não as tags em si). Isso permite reusar a mesma estrutura de saída entre as duas funções.

---

## Como Executar

### Pré-requisitos

- Java 21
- Maven 3.6+
- Os 4 arquivos JSON em `data/`

### Compilar e rodar

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="bg3builder.Main"
```

Ou via IntelliJ: rodar `Main.java` diretamente.

A `Main` carrega os dados, constrói os índices, e demonstra ambas as funcionalidades imprimindo o JSON de entrada e o JSON de resposta para vários casos de teste.

---

## Resumo do Fluxo de Execução

1. **Inicialização** (`Main`): `DataLoader` lê os 4 JSONs → `Indexes` precomputa lookups → `BuildExpander`, `FeatureSearch`, `TagSearch`, `BuildMaterializer`, `BuildAPI` são instanciados.

2. **Requisição feature** (`BuildAPI.findBuildForFeatures`):
    - Parse do JSON de entrada
    - `FeatureSearch.findSmallestBuild` roda A* até encontrar a menor build
    - `BuildExpander.reachableFeatures` é chamado para verificar quais alvos foram satisfeitos
    - `BuildMaterializer.materialize` converte a build em `BuildPlan` detalhado
    - Jackson serializa o `BuildPlan` em JSON

3. **Requisição tag** (`BuildAPI.findBuildsForTags`):
    - Parse do JSON
    - `TagSearch.findBestBuilds` enumera builds top-K e pontua
    - Para cada uma das top N, `BuildMaterializer.materialize` é chamado
    - Lista de planos é serializada como `TagSearchResponse`

---

## Limitações Conhecidas

- **Subclasses são tratadas como capacidade completa**: builds podem "alcançar" features de subclasses mutuamente exclusivas simultaneamente. Em raras consultas (ex: "frenzy AND wild_magic_surge"), o algoritmo pode retornar uma build que apenas com uma subclasse não daria os dois — mas isso só importa se o usuário pede features de subclasses diferentes do mesmo pai, o que é incomum.
- **Dependências entre escolhas (`requires`)**: features como `deepened_pact_blade_extra_attack` que dependem de outra escolha (ex: ter pegado Pact of the Blade) não são filtradas no expander. Pequena imprecisão, geralmente irrelevante para o resultado da busca.
- **Casts pre-pickeados não são modelados**: o sistema é "capacity-based" — saber que uma build _pode_ pegar Fireball é diferente de saber que ela _pegou_. Para nossas duas funcionalidades, capacidade é o que importa.