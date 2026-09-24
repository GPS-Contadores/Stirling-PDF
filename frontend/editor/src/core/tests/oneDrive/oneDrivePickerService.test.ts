import { describe, expect, it, vi } from "vitest";
import {
  buildPickerOptions,
  handlePickerMessage,
  PickerCommandHandlers,
  pickerPageUrl,
} from "@app/services/oneDrivePickerService";

function setup(overrides: Partial<PickerCommandHandlers> = {}) {
  const posted: unknown[] = [];
  const handlers: PickerCommandHandlers = {
    getToken: vi.fn(async () => "token-123"),
    onPick: vi.fn(),
    onClose: vi.fn(),
    ...overrides,
  };
  return { posted, post: (m: unknown) => posted.push(m), handlers };
}

describe("buildPickerOptions", () => {
  it("opens files with multi-select and host-supplied tokens", () => {
    const options = buildPickerOptions({
      channelId: "c1",
      origin: "https://docs.example.com",
      mode: "files",
      multiple: true,
    });
    expect(options.sdk).toBe("8.0");
    expect(options.entry).toEqual({ oneDrive: { files: {} } });
    expect(options.authentication).toEqual({});
    expect(options.messaging).toEqual({
      origin: "https://docs.example.com",
      channelId: "c1",
    });
    expect(options.typesAndSources.mode).toBe("files");
    expect(options.typesAndSources).not.toHaveProperty("access");
    expect(options.selection.mode).toBe("multiple");
  });

  it("asks for a writable single folder when saving", () => {
    const options = buildPickerOptions({
      channelId: "c1",
      origin: "https://docs.example.com",
      mode: "folders",
      multiple: false,
      pickLabel: "Salvar aqui",
    });
    expect(options.typesAndSources.mode).toBe("folders");
    expect(options.typesAndSources).toHaveProperty("access", {
      mode: "read-write",
    });
    expect(options.selection.mode).toBe("single");
    expect(options.commands.pick).toEqual({ label: "Salvar aqui" });
  });
});

describe("pickerPageUrl", () => {
  it("opens the picker on the user's site, not on the host root", () => {
    expect(
      pickerPageUrl(
        "https://contoso-my.sharepoint.com/personal/ana_contoso_com",
      ),
    ).toBe(
      "https://contoso-my.sharepoint.com/personal/ana_contoso_com/_layouts/15/FilePicker.aspx",
    );
  });

  it("does not double the slash", () => {
    expect(
      pickerPageUrl("https://contoso-my.sharepoint.com/personal/ana/"),
    ).toBe(
      "https://contoso-my.sharepoint.com/personal/ana/_layouts/15/FilePicker.aspx",
    );
  });
});

describe("handlePickerMessage", () => {
  it("ignores notifications", async () => {
    const { posted, post, handlers } = setup();
    await handlePickerMessage(
      { type: "notification", data: { notification: "page-loaded" } },
      post,
      handlers,
    );
    expect(posted).toEqual([]);
  });

  it("acknowledges and answers authenticate with a token for the resource", async () => {
    const { posted, post, handlers } = setup();
    await handlePickerMessage(
      {
        type: "command",
        id: "7",
        data: {
          command: "authenticate",
          resource: "https://contoso.sharepoint.com",
        },
      },
      post,
      handlers,
    );
    expect(handlers.getToken).toHaveBeenCalledWith(
      "https://contoso.sharepoint.com",
    );
    expect(posted).toEqual([
      { type: "acknowledge", id: "7" },
      {
        type: "result",
        id: "7",
        data: { result: "token", token: "token-123" },
      },
    ]);
  });

  it("reports a token failure to the picker instead of throwing", async () => {
    const { posted, post, handlers } = setup({
      getToken: async () => {
        throw new Error("consent required");
      },
    });
    await handlePickerMessage(
      { type: "command", id: "8", data: { command: "authenticate" } },
      post,
      handlers,
    );
    expect(posted[1]).toEqual({
      type: "result",
      id: "8",
      data: {
        result: "error",
        error: { code: "unableToObtainToken", message: "consent required" },
      },
    });
  });

  it("confirms a pick and hands over the items", async () => {
    const { posted, post, handlers } = setup();
    const items = [{ id: "i1", parentReference: { driveId: "d1" } }];
    await handlePickerMessage(
      { type: "command", id: "9", data: { command: "pick", items } },
      post,
      handlers,
    );
    expect(posted).toEqual([
      { type: "acknowledge", id: "9" },
      { type: "result", id: "9", data: { result: "success" } },
    ]);
    expect(handlers.onPick).toHaveBeenCalledWith(items);
  });

  it("closes on the close command", async () => {
    const { post, handlers } = setup();
    await handlePickerMessage(
      { type: "command", id: "10", data: { command: "close" } },
      post,
      handlers,
    );
    expect(handlers.onClose).toHaveBeenCalled();
  });

  it("answers unknown commands so the picker doesn't wait forever", async () => {
    const { posted, post, handlers } = setup();
    await handlePickerMessage(
      { type: "command", id: "11", data: { command: "custom" } },
      post,
      handlers,
    );
    expect(posted[1]).toEqual({
      type: "result",
      id: "11",
      data: {
        result: "error",
        error: { code: "unsupportedCommand", message: "custom" },
      },
    });
  });
});
