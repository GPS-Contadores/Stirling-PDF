/**
 * Microsoft File Picker v8 (GPS fork).
 * https://learn.microsoft.com/onedrive/developer/controls/file-pickers/
 *
 * The picker is a SharePoint page POSTed into an iframe and driven by
 * postMessage over a MessageChannel. It is hosted in an iframe overlay, not a
 * popup: the login may already need a popup, and a second one opened from
 * the same click would be blocked.
 */

import { Z_INDEX_OVER_FILE_MANAGER_MODAL } from "@app/styles/zIndex";

/** Fields the picker always returns for a picked item. */
export interface PickedItem {
  id: string;
  name?: string;
  parentReference: { driveId: string };
  "@sharePoint.endpoint"?: string;
}

export type PickerMode = "files" | "folders";

export interface PickerOptionsInput {
  channelId: string;
  origin: string;
  mode: PickerMode;
  multiple: boolean;
  /** Label for the pick button; must already be localised. */
  pickLabel?: string;
  theme?: "default" | "dark";
}

export function buildPickerOptions(input: PickerOptionsInput) {
  return {
    sdk: "8.0",
    entry: { oneDrive: {} },
    // Present (even empty) means the host supplies tokens; required in an iframe.
    authentication: {},
    messaging: { origin: input.origin, channelId: input.channelId },
    typesAndSources: {
      mode: input.mode,
      pivots: {
        oneDrive: true,
        recent: true,
        shared: true,
        sharedLibraries: true,
      },
      // Saving needs a folder the user can write to.
      ...(input.mode === "folders" ? { access: { mode: "read-write" } } : {}),
    },
    selection: { mode: input.multiple ? "multiple" : "single" },
    commands: {
      pick: input.pickLabel ? { label: input.pickLabel } : {},
    },
    theme: input.theme ?? "default",
  };
}

type PostMessage = (message: unknown) => void;

/** What arrives on the picker's port (only the fields we read). */
interface PickerPortMessage {
  type?: string;
  id?: string;
  data?: {
    command?: string;
    resource?: string;
    items?: unknown;
    notification?: string;
  };
}

export interface PickerCommandHandlers {
  getToken: (resource: string) => Promise<string>;
  onPick: (items: PickedItem[]) => void;
  onClose: () => void;
}

/**
 * Handles one message from the picker's port. Every command is acknowledged
 * and answered, including the ones we don't support, or the picker hangs.
 */
export async function handlePickerMessage(
  data: PickerPortMessage | null | undefined,
  post: PostMessage,
  handlers: PickerCommandHandlers,
): Promise<void> {
  if (data?.type !== "command") return;

  const id = data.id;
  const command = data.data ?? {};
  post({ type: "acknowledge", id });

  const reply = (result: object) => post({ type: "result", id, data: result });
  const fail = (code: string, message: string) =>
    reply({ result: "error", error: { code, message } });

  switch (command.command) {
    case "authenticate":
      try {
        const token = await handlers.getToken(String(command.resource));
        reply({ result: "token", token });
      } catch (error) {
        fail("unableToObtainToken", errorMessage(error));
      }
      return;

    case "pick":
      reply({ result: "success" });
      handlers.onPick(
        Array.isArray(command.items) ? (command.items as PickedItem[]) : [],
      );
      return;

    case "close":
      handlers.onClose();
      return;

    default:
      fail("unsupportedCommand", String(command.command));
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export interface OpenPickerParams {
  baseUrl: string;
  locale: string;
  mode: PickerMode;
  multiple: boolean;
  pickLabel?: string;
  closeLabel: string;
  /** Token for `resource`; the first call is for `baseUrl` itself. */
  getToken: (resource: string) => Promise<string>;
}

/**
 * Opens the picker over the page. Resolves with the picked items, or [] when
 * the user closes it.
 */
export async function openOneDrivePicker(
  params: OpenPickerParams,
): Promise<PickedItem[]> {
  // The form carries a token, so fetch it before anything is shown.
  const initialToken = await params.getToken(params.baseUrl);

  const channelId = crypto.randomUUID();
  const pickerOrigin = new URL(params.baseUrl).origin;
  const theme =
    document.documentElement.getAttribute("data-mantine-color-scheme") ===
    "dark"
      ? "dark"
      : "default";
  const options = buildPickerOptions({
    channelId,
    origin: window.location.origin,
    mode: params.mode,
    multiple: params.multiple,
    pickLabel: params.pickLabel,
    theme,
  });

  const overlay = createOverlay(channelId, params.closeLabel);
  document.body.appendChild(overlay.root);

  return new Promise<PickedItem[]>((resolve) => {
    let port: MessagePort | null = null;
    let settled = false;

    const finish = (items: PickedItem[]) => {
      if (settled) return;
      settled = true;
      window.removeEventListener("message", onWindowMessage);
      document.removeEventListener("keydown", onKeyDown, true);
      port?.close();
      overlay.root.remove();
      resolve(items);
    };

    const handlers: PickerCommandHandlers = {
      getToken: params.getToken,
      onPick: (items) => finish(items),
      onClose: () => finish([]),
    };

    const onWindowMessage = (event: MessageEvent) => {
      if (event.source !== overlay.iframe.contentWindow) return;
      if (event.origin !== pickerOrigin) return;
      const message = event.data;
      if (message?.type !== "initialize" || message.channelId !== channelId) {
        return;
      }
      // The picker re-sends "initialize" if it reloads; drop the old port.
      port?.close();
      port = event.ports[0];
      const currentPort = port;
      currentPort.addEventListener("message", (portEvent) => {
        void handlePickerMessage(
          portEvent.data,
          (reply) => currentPort.postMessage(reply),
          handlers,
        );
      });
      currentPort.start();
      currentPort.postMessage({ type: "activate" });
    };

    // Capture phase + stopPropagation: Escape must close only the picker, not
    // the file manager modal underneath it as well.
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.stopPropagation();
      finish([]);
    };

    window.addEventListener("message", onWindowMessage);
    document.addEventListener("keydown", onKeyDown, true);
    overlay.closeButton.addEventListener("click", () => finish([]));

    const query = new URLSearchParams({
      filePicker: JSON.stringify(options),
      locale: params.locale,
    });
    postTokenForm(
      `${pickerOrigin}/_layouts/15/FilePicker.aspx?${query}`,
      overlay.iframe.name,
      initialToken,
    );
  });
}

function createOverlay(channelId: string, closeLabel: string) {
  const root = document.createElement("div");
  root.setAttribute("role", "dialog");
  root.setAttribute("aria-modal", "true");
  root.setAttribute("aria-label", "OneDrive");
  Object.assign(root.style, {
    position: "fixed",
    inset: "0",
    zIndex: String(Z_INDEX_OVER_FILE_MANAGER_MODAL),
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    background: "rgba(0, 0, 0, 0.45)",
  });

  const frame = document.createElement("div");
  Object.assign(frame.style, {
    // The picker's recommended maximum size is 1080x680.
    width: "min(1080px, 96vw)",
    height: "min(680px, 92vh)",
    borderRadius: "8px",
    overflow: "hidden",
    background: "var(--mantine-color-body, #fff)",
    boxShadow: "0 12px 40px rgba(0, 0, 0, 0.3)",
  });

  const iframe = document.createElement("iframe");
  iframe.name = `onedrive-picker-${channelId}`;
  iframe.title = "OneDrive";
  Object.assign(iframe.style, {
    width: "100%",
    height: "100%",
    border: "0",
  });

  const closeButton = document.createElement("button");
  closeButton.type = "button";
  closeButton.textContent = "×";
  closeButton.setAttribute("aria-label", closeLabel);
  closeButton.title = closeLabel;
  // In the overlay corner, not over the picker, whose header has its own controls.
  Object.assign(closeButton.style, {
    position: "absolute",
    top: "12px",
    right: "16px",
    width: "36px",
    height: "36px",
    border: "0",
    borderRadius: "50%",
    background: "rgba(0, 0, 0, 0.35)",
    color: "#fff",
    fontSize: "24px",
    lineHeight: "1",
    cursor: "pointer",
  });

  frame.appendChild(iframe);
  root.append(frame, closeButton);
  return { root, iframe, closeButton };
}

function postTokenForm(action: string, target: string, token: string) {
  const form = document.createElement("form");
  form.method = "POST";
  form.action = action;
  form.target = target;
  form.style.display = "none";

  const input = document.createElement("input");
  input.type = "hidden";
  input.name = "access_token";
  input.value = token;

  form.appendChild(input);
  document.body.appendChild(form);
  form.submit();
  form.remove();
}
