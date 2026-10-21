# UI e navegação

Stack: **Jetpack Compose** + Material 3, edge-to-edge (`OneGalleryEdgeToEdge`).

## Entrada

- `MainActivity` → `OneGalleryTheme` → `OneGalleryApp`.
- Permissões de mídia e notificação solicitadas conforme API level.
- `MediaDeleteRequestRouter` registrado para resultado de intent de exclusão scoped.

## Fluxo antes das abas

Controlado por `SyncPreferences.hasCompletedSetup` e passos de nuvem:

| Etapa | Composable |
|-------|------------|
| Boas-vindas | `WelcomeScreen` |
| Login Microsoft | `SignInGate` |
| Escolher pasta Graph | `OneDriveCloudBrowserScreen` |
| Alternativa SAF | `OneDriveSafBrowserScreen` |

Após setup: **quatro abas** (`MainTab`).

## Abas principais

| Tab | Rota | Screen |
|-----|------|--------|
| Galeria | `gallery` | `GalleryScreen` |
| Álbuns | `albums` | `AlbumsScreen` |
| Sync | `sync` | `SyncStatusScreen` |
| Ajustes | `settings` | `SettingsScreen` |

## Rotas aninhadas

| Rota | Destino |
|------|---------|
| `albums/browse` | `GalleryScreen` filtrada por álbum (tab Álbuns permanece selecionada) |
| `places_map` | `PlacesMapScreen` |
| `media/{encodedPath}` | `MediaDetailScreen` (path URL-encoded) |

Helpers em `OneGalleryApp.kt`: `openGalleryTab`, `openAlbumsTab`, `openMainTab`, handlers de Back para modos de seleção.

## SyncStatusScreen

- Card de status (conexão, fase, headline/detail).
- **Transferências em paralelo:** lista `activeItems` com `SyncActiveTransferRow` + barra de progresso.
- Fila: `runQueue` durante sync ou `syncQueue` do Room quando idle.
- Estatísticas derivadas de metadata (problemas, pendentes).
- Botões: sincronizar agora, limpar erros, confirmar exclusões.

## SettingsScreen

- Modo sync, Wi‑Fi only, background always on, auto-release, conta Microsoft, pasta Graph, diagnóstico.

## Tema

- `ui/theme/Theme.kt`, `Color.kt`, `Type.kt`
- Insets: `OneGalleryInsets.kt`, `ZeroScaffoldContentInsets` onde aplicável.

## Imagens

- **Coil** configurado na Application (memória + disco + decoder de vídeo).

## Mapa

- **MapLibre** inicializado na Application.
- Permissão de localização opcional para centralizar mapa (`MapDeviceLocation.kt`).
