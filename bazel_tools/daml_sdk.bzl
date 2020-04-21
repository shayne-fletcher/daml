# Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

def _daml_sdk_impl(ctx):
    ctx.download_and_extract(
        output = "extracted-sdk",
        url =
            "https://github.com/digital-asset/daml/releases/download/v{}/daml-sdk-{}-linux.tar.gz".format(ctx.attr.version, ctx.attr.version),
        sha256 = ctx.attr.sha256,
        stripPrefix = "sdk-{}".format(ctx.attr.version),
    )
    ps_result = ctx.execute(
        ["extracted-sdk/daml/daml", "install", "extracted-sdk", "--install-assistant=no"],
        environment = {
            "DAML_HOME": "sdk",
        },
    )
    if ps_result.return_code != 0:
        fail("Failed to install SDK.\nExit code %d.\n%s\n%s" %
             (ps_result.return_code, ps_result.stdout, ps_result.stderr))

    # At least on older SDKs, the symlinking in --install-assistant=yes does not work
    # properly so we symlink ourselves.
    ctx.symlink("sdk/sdk/{}/daml/daml".format(ctx.attr.version), "sdk/bin/daml")
    ctx.file(
        "sdk/daml-config.yaml",
        content =
            """
auto-install: false
update-check: never
""",
    )
    ctx.file(
        "BUILD",
        content =
            """
package(default_visibility = ["//visibility:public"])
filegroup(
  name = "all",
  srcs = ["sdk/**"],
)
exports_files(glob(["sdk/**"]))
""",
    )
    return None

daml_sdk = repository_rule(
    implementation = _daml_sdk_impl,
    attrs = {
        "version": attr.string(mandatory = True),
        "sha256": attr.string(mandatory = True),
    },
)
