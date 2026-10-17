export type LicenseEntry = {
  name: string;
  license: string;
};

export const LICENSES: readonly LicenseEntry[] = [
  { name: 'ONNX Runtime', license: 'MIT' },
  { name: 'ISNet', license: 'MIT' },
  { name: 'AndroidSVG', license: 'Apache-2.0' },
  { name: 'React Native', license: 'MIT' },
  { name: 'Hermes', license: 'MIT' },
  { name: 'React Native Reusables', license: 'MIT' },
  { name: 'React Native Free Canvas (adapted)', license: "MIT License\n\nCopyright (c) 2024 Lam<whatisurname7@gmail.com>\n\nPermission is hereby granted, free of charge, to any person obtaining a copy\nof this software and associated documentation files (the \"Software\"), to deal\nin the Software without restriction, including without limitation the rights\nto use, copy, modify, merge, publish, distribute, sublicense, and/or sell\ncopies of the Software, and to permit persons to whom the Software is\nfurnished to do so, subject to the following conditions:\n\nThe above copyright notice and this permission notice shall be included in all\ncopies or substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\nIMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\nFITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\nAUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\nLIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\nOUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\nSOFTWARE.\n" },
  { name: 'React Native Skia', license: 'MIT' },
  { name: 'React Native Gesture Handler', license: 'MIT' },
  { name: 'React Native Reanimated', license: 'MIT' },
  { name: 'Firebase SDKs', license: 'Apache-2.0' },
];

const REQUIRED = ['ONNX Runtime', 'ISNet', 'AndroidSVG', 'React Native', 'Hermes', 'React Native Reusables'];

export function hasRequiredLicenses(entries: readonly LicenseEntry[] = LICENSES): boolean {
  const names = new Set(entries.map(entry => entry.name));
  return REQUIRED.every(name => names.has(name));
}
