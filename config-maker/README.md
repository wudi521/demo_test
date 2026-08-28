# VelaGate Config Maker

Cross-platform signing tool for VelaGate `.conf` files.

## Important

The app does **not** contain the signing private key. Keep `velagate_signing_private.pem` private and place it next to the app, or select it with **Browse** on first launch.

Never send the private key to customers. Only send the generated `.conf` files.

## Generate a matched pair

1. Open the app.
2. Check/edit Europe Route nodes.
3. Check/edit Traffic Package fields.
4. Click **Generate Matched Pair**.
5. Send the two generated `.conf` files to one customer/device.

Every click creates new unique `fileId` values and fresh RSA signatures.
