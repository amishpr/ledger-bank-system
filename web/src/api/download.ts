// Hands a generated file to the browser as a download. Used by both
// backends so the export behaves identically whether the CSV came over
// HTTP or was built in this tab.
export function saveBlob(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
