import { createHash } from 'node:crypto';
import { readFile, readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const lockfilePath = path.join(projectRoot, 'package-lock.json');
const outputPath = path.join(projectRoot, 'WEB_THIRD_PARTY_LICENSES.txt');

const vadLicense = `ISC License

Copyright (c) 2022-present ricky0123

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

---

The file silero_vad.onnx falls under the following license:

---

MIT License

Copyright (c) 2020-present Silero Team

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;

const onnxRuntimeLicense = `MIT License

Copyright (c) Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;

const guidTypescriptLicense = `ISC License

Copyright holder: nicolas (the author declared by guid-typescript 1.0.9 package metadata)

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.`;

const qrCodeGeneratorLicense = `MIT License

Copyright (c) 2009 Kazuhiko Arase

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;

// These exact package versions declare an SPDX license but omit the license
// file from their npm tarball. Keep fallbacks version-pinned so an upgrade with
// different terms fails instead of silently reusing stale legal text.
const licenseFallbacks = new Map([
  [
    'qrcode-generator@2.0.4',
    {
      expectedLicense: 'MIT',
      source:
        'https://github.com/kazuhikoarase/qrcode-generator/blob/83b7e8fe3fddd3b0368dbafd6ce56995bd25e3c8/LICENSE',
      sha256: '3a850fa5f08101db6f40676c2786e10bd2cd5fff7b12ffdf1e0c434d4e49d90c',
      text: qrCodeGeneratorLicense,
    },
  ],
  [
    '@ricky0123/vad-web@0.0.30',
    {
      expectedLicense: 'ISC',
      source:
        'https://github.com/ricky0123/vad/blob/8941bbf9116234748934d6b563c1751ce4d43c35/LICENSE',
      sha256: '239ebca4207803d3507124517c0dd6324c0d5cf73e1c486ade16bac1788f4a79',
      text: vadLicense,
    },
  ],
  [
    'guid-typescript@1.0.9',
    {
      expectedLicense: 'ISC',
      source:
        'package metadata (author: nicolas, license: ISC) and https://spdx.org/licenses/ISC.html',
      sha256: 'b7f68285925792a4c9eb177b05175a9beeac1e943a7a160370a3b3dc3624a9be',
      text: guidTypescriptLicense,
    },
  ],
  [
    'onnxruntime-common@1.23.2',
    {
      expectedLicense: 'MIT',
      source:
        'https://github.com/microsoft/onnxruntime/blob/v1.23.2/LICENSE',
      sha256: '2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c',
      text: onnxRuntimeLicense,
    },
  ],
  [
    'onnxruntime-web@1.23.2',
    {
      expectedLicense: 'MIT',
      source:
        'https://github.com/microsoft/onnxruntime/blob/v1.23.2/LICENSE',
      sha256: '2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c',
      text: onnxRuntimeLicense,
    },
  ],
]);

const licenseFilePattern = /^(?:licen[cs]e|copying)(?:[-_.].*)?$/i;
const noticeFilePattern = /^(?:notice)(?:[-_.].*)?$|^third[-_. ]?party(?:[-_. ].*)?$/i;

function normalizeText(value) {
  return value.replace(/\r\n?/g, '\n').replace(/[ \t]+$/gm, '').trim();
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function compareText(left, right) {
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

async function isFile(filePath) {
  try {
    return (await stat(filePath)).isFile();
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

async function readLegalFile(packageDirectory, fileName) {
  const filePath = path.join(packageDirectory, fileName);
  const contents = normalizeText(await readFile(filePath, 'utf8'));

  if (!contents) {
    throw new Error(`Legal file is empty: ${path.relative(projectRoot, filePath)}`);
  }

  if (contents.includes('\0')) {
    throw new Error(`Legal file is not plain text: ${path.relative(projectRoot, filePath)}`);
  }

  return {
    label: fileName,
    source: path.relative(projectRoot, filePath),
    text: contents,
  };
}

async function loadPackage(lockPath, lockEntry) {
  const packageDirectory = path.join(projectRoot, lockPath);
  const packageJsonPath = path.join(packageDirectory, 'package.json');

  if (!(await isFile(packageJsonPath))) {
    throw new Error(
      `Missing ${path.relative(projectRoot, packageJsonPath)}; run npm ci before generating licenses.`,
    );
  }

  const packageJson = JSON.parse(await readFile(packageJsonPath, 'utf8'));
  const name = packageJson.name;
  const version = packageJson.version;
  const declaredLicense = packageJson.license ?? lockEntry.license;

  if (!name || !version) {
    throw new Error(`Package metadata is incomplete: ${lockPath}`);
  }

  if (version !== lockEntry.version) {
    throw new Error(
      `Installed ${name}@${version} does not match package-lock version ${lockEntry.version}. Run npm ci.`,
    );
  }

  if (!declaredLicense) {
    throw new Error(`No declared license for ${name}@${version}.`);
  }

  if (
    packageJson.license &&
    lockEntry.license &&
    packageJson.license !== lockEntry.license
  ) {
    throw new Error(
      `License mismatch for ${name}@${version}: package.json=${packageJson.license}, package-lock=${lockEntry.license}.`,
    );
  }

  const directoryEntries = (await readdir(packageDirectory)).sort(compareText);
  const licenseFileNames = [];
  const noticeFileNames = [];

  for (const fileName of directoryEntries) {
    const filePath = path.join(packageDirectory, fileName);
    if (!(await isFile(filePath))) continue;
    if (licenseFilePattern.test(fileName)) licenseFileNames.push(fileName);
    if (noticeFilePattern.test(fileName)) noticeFileNames.push(fileName);
  }

  const legalDocuments = [];
  for (const fileName of licenseFileNames) {
    legalDocuments.push(await readLegalFile(packageDirectory, fileName));
  }

  if (licenseFileNames.length === 0) {
    const packageId = `${name}@${version}`;
    const fallback = licenseFallbacks.get(packageId);

    if (!fallback) {
      throw new Error(
        `${packageId} declares ${declaredLicense} but ships no license file and has no audited fallback.`,
      );
    }

    if (declaredLicense !== fallback.expectedLicense) {
      throw new Error(
        `${packageId} fallback expects ${fallback.expectedLicense}, but package metadata declares ${declaredLicense}.`,
      );
    }

    const fallbackText = normalizeText(fallback.text);
    const fallbackHash = sha256(`${fallbackText}\n`);
    if (fallback.sha256 && fallbackHash !== fallback.sha256) {
      throw new Error(
        `${packageId} embedded fallback hash mismatch: expected ${fallback.sha256}, got ${fallbackHash}.`,
      );
    }

    legalDocuments.push({
      label: 'AUDITED LICENSE FALLBACK',
      source: fallback.source,
      text: fallbackText,
    });
  }

  for (const fileName of noticeFileNames) {
    legalDocuments.push(await readLegalFile(packageDirectory, fileName));
  }

  return {
    declaredLicense,
    legalDocuments,
    lockPath,
    name,
    version,
  };
}

function formatPackage(packageInfo) {
  const divider = '='.repeat(80);
  const documentDivider = '-'.repeat(80);
  const sections = [
    divider,
    `PACKAGE: ${packageInfo.name}@${packageInfo.version}`,
    `LOCK PATH: ${packageInfo.lockPath}`,
    `DECLARED LICENSE: ${packageInfo.declaredLicense}`,
  ];

  for (const document of packageInfo.legalDocuments) {
    sections.push(
      '',
      `DOCUMENT: ${document.label}`,
      `SOURCE: ${document.source}`,
      documentDivider,
      document.text,
    );
  }

  return sections.join('\n');
}

async function main() {
  const lockfileContents = normalizeText(await readFile(lockfilePath, 'utf8'));
  const lockfile = JSON.parse(lockfileContents);

  if (lockfile.lockfileVersion !== 3 || !lockfile.packages) {
    throw new Error('Expected package-lock.json lockfileVersion 3 with a packages table.');
  }

  const productionEntries = Object.entries(lockfile.packages)
    .filter(
      ([lockPath, lockEntry]) =>
        lockPath.includes('node_modules/') && lockEntry.dev !== true,
    )
    .sort(([left], [right]) => compareText(left, right));

  const packages = [];
  for (const [lockPath, lockEntry] of productionEntries) {
    packages.push(await loadPackage(lockPath, lockEntry));
  }

  packages.sort((left, right) => {
    return (
      compareText(left.name, right.name) ||
      compareText(left.version, right.version) ||
      compareText(left.lockPath, right.lockPath)
    );
  });

  const header = [
    'WEB THIRD-PARTY LICENSES',
    '',
    'Generated by: npm run licenses:web',
    'Dependency scope: package-lock.json entries not marked dev-only',
    `Lockfile SHA-256: ${sha256(`${lockfileContents}\n`)}`,
    `Production package count: ${packages.length}`,
    '',
    'This file is deterministic and generated. Do not edit it manually.',
    'The generator fails if a production package has no license text or audited fallback.',
    '',
  ].join('\n');

  const output = `${header}${packages.map(formatPackage).join('\n\n')}\n`;
  await writeFile(outputPath, output, 'utf8');
  console.log(`Wrote ${path.relative(projectRoot, outputPath)} for ${packages.length} packages.`);
}

main().catch((error) => {
  console.error(`License generation failed: ${error.message}`);
  process.exitCode = 1;
});
