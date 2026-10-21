# Exclusões e liberação de espaço

## Filosofia

Exclusões detectadas durante sync **não são aplicadas automaticamente** em ambos os lados. O usuário confirma na UI; até lá, `pendingDeleteTarget` marca a intenção.

## Excluir no aparelho (Android 11+)

Por política do Android, apps com só “fotos e vídeos” **não podem** apagar arquivos de outros apps sem um diálogo do sistema (`MediaStore.createDeleteRequest`).

**Uma vez em Ajustes → “Excluir sem confirmar toda vez”:** concede `MANAGE_EXTERNAL_STORAGE` (“Acesso a todos os arquivos”). Depois disso, o app apaga via caminho + MediaStore **sem pedir permissão a cada exclusão** (`MediaStorageAccess`, `MediaDeleteRequestRouter`).

Sem esse acesso, cada lote de exclusão ainda exige confirmação do Android — limitação do SO, não escolha do app.

## Fluxo de exclusão

1. **Reconciliação** detecta: arquivo sumiu localmente ou remotamente, ou usuário pediu delete na UI.
2. Linha atualizada com `PendingDeleteTarget` adequado.
3. Item aparece em **Sync → aguardando confirmação** (e fluxos de diálogo no detalhe/grade).
4. `resolvePendingDelete` executa:
   - Remoção MediaStore (com `MediaDeleteRequestRouter` / intent sender em Android 11+)
   - `deleteRemoteItem` no Graph ou SAF/espelho
5. Linha removida ou estado limpo.

## Exclusão imediata

`deleteItemsImmediately` — caminho rápido quando o usuário já confirmou na grade (seleção múltipla).

## Liberar espaço local (`localReleasedForSpace`)

Quando o usuário (ou política automática) remove a cópia no aparelho mas mantém o item na nuvem:

- Flag **`localReleasedForSpace = true`**
- **Não** interpretar como “usuário deletou local” para propagar exclusão na nuvem.
- Item continua visível na galeria se houver miniatura (modo cloud-only).

### Auto-release

- Preferência: dias sem abrir o item (`DEFAULT_LOCAL_IDLE_RELEASE_DAYS` = 90, faixa 1–180).
- `runAutoReleaseIfNeeded()` na Application quando app volta ao foreground.
- Só se `autoFreeLocalSpaceWhenSynced` ligado.

### Após release

- Supressão temporária do **MediaLibraryChangeWatcher** para não disparar sync em loop.

## Lixeira

- Itens em pastas de lixeira são **ignorados** na indexação (`SyncPolicy.shouldIncludeLocalItem`).

## Erros

- Falhas ficam em `lastError` + `CloudSyncState.ERROR`.
- Aba Sync lista itens com atenção (`shouldShowAttentionItem`).
