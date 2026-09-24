import { describe, expect, it } from "vitest";
import { getOneDriveConfig } from "@app/services/oneDriveConfig";

const complete = {
  VITE_GPS_ENTRA_CLIENT_ID: "00000000-0000-0000-0000-000000000000",
  VITE_GPS_TENANT: "contoso.onmicrosoft.com",
  VITE_GPS_SHAREPOINT_HOST: "contoso-my.sharepoint.com",
};

describe("getOneDriveConfig", () => {
  it("builds the picker origin from a bare host", () => {
    expect(getOneDriveConfig(complete)).toEqual({
      clientId: complete.VITE_GPS_ENTRA_CLIENT_ID,
      tenant: "contoso.onmicrosoft.com",
      pickerBaseUrl: "https://contoso-my.sharepoint.com",
    });
  });

  it("accepts a full URL and keeps only its origin", () => {
    const config = getOneDriveConfig({
      ...complete,
      VITE_GPS_SHAREPOINT_HOST:
        " https://contoso-my.sharepoint.com/personal/x ",
    });
    expect(config?.pickerBaseUrl).toBe("https://contoso-my.sharepoint.com");
  });

  it.each([
    "VITE_GPS_ENTRA_CLIENT_ID",
    "VITE_GPS_TENANT",
    "VITE_GPS_SHAREPOINT_HOST",
  ] as const)("is off when %s is missing or blank", (name) => {
    expect(getOneDriveConfig({ ...complete, [name]: undefined })).toBeNull();
    expect(getOneDriveConfig({ ...complete, [name]: "  " })).toBeNull();
  });

  it("refuses a non-https host", () => {
    expect(
      getOneDriveConfig({
        ...complete,
        VITE_GPS_SHAREPOINT_HOST: "http://contoso-my.sharepoint.com",
      }),
    ).toBeNull();
  });
});
