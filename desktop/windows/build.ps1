$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
python tools/prepare_model.py
if ($LASTEXITCODE -ne 0) { throw 'Pinned speech model preparation failed' }
python -m PyInstaller --noconfirm --clean --noupx --onedir --windowed --name EMA --paths . --add-data 'resources;resources' --collect-all vosk --collect-all sounddevice --collect-submodules comtypes launcher.py
if ($LASTEXITCODE -ne 0) { throw 'Desktop app build failed' }
python -m PyInstaller --noconfirm --clean --noupx --onedir --console --name EMA-worker --paths . --distpath worker-dist worker_launcher.py
if ($LASTEXITCODE -ne 0) { throw 'UI Automation worker build failed' }
Move-Item -Force worker-dist/EMA-worker dist/EMA/worker
Copy-Item README.md dist/EMA/README.md
python tools/collect_licenses.py dist/EMA
if ($LASTEXITCODE -ne 0) { throw 'License collection failed' }
python tools/verify_distribution.py dist/EMA
if ($LASTEXITCODE -ne 0) { throw 'Distribution verification failed' }
$app = Start-Process -FilePath dist/EMA/EMA.exe -ArgumentList '--self-test' -PassThru -Wait
if ($app.ExitCode -ne 0) { throw 'Packaged desktop dependency/model smoke test failed' }
$gui = Start-Process -FilePath dist/EMA/EMA.exe -ArgumentList '--gui-smoke-test' -PassThru -Wait
if ($gui.ExitCode -ne 0) { throw 'Packaged desktop GUI startup smoke test failed' }
$worker = Start-Process -FilePath dist/EMA/worker/EMA-worker.exe -ArgumentList '--self-test' -PassThru -Wait -WindowStyle Hidden
if ($worker.ExitCode -ne 0) { throw 'Packaged UI Automation worker smoke test failed' }
Write-Host 'PASS: Windows portable app, model, licenses and worker built and smoke-tested'
