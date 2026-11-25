# Third-party notices

This repository is MIT-licensed (see `LICENSE`) except for the third-party material below,
which keeps its own licence.

## PaPaGei (Nokia Bell Labs)

- Code: `ml-inference-server/papagei/` is a git submodule of
  <https://github.com/Nokia-Bell-Labs/papagei-foundation-model>
  (clone with `git clone --recurse-submodules`).
- Weights: `ml-inference-server/weights/papagei_s.pt` is the PaPaGei-S checkpoint published
  on Zenodo (<https://zenodo.org/records/13983110>); `papagei_s.onnx` is an ONNX export of it
  made by `ml-inference-server/export_onnx.py`.
- Reference: A. Pillai et al., *PaPaGei: Open Foundation Models for Optical Physiological
  Signals*, ICLR 2025.
- The models were trained by their authors on VitalDB, MIMIC-III and MESA; none of that data
  is included here.

Licence of the PaPaGei code, as distributed with it:

```text
BSD 3-Clause License

Copyright (c) 2024, Device Research at Nokia Bell Labs

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

The trained heads in `ml-inference-server/heads/` were trained in this project and are
covered by this repository's MIT licence.

## Other dependencies

Rust crates, npm packages, Python packages and Android libraries are fetched by their
package managers under their own licences and are not vendored here.
