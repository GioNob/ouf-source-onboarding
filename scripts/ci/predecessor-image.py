"""Verify the pinned Onboarding CI artifact and return its loaded Docker image ID."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
from zipfile import ZipFile


def output(*args):
    return subprocess.check_output(args, text=True).strip()


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load_predecessor(work):
    repository = os.environ["GITHUB_REPOSITORY"]
    commit = os.environ["PREDECESSOR_COMMIT"]
    run = os.environ["PREDECESSOR_RUN"]
    artifact = os.environ["PREDECESSOR_ARTIFACT"]
    image = os.environ["PREDECESSOR_IMAGE_ID"]
    tree = output("git", "rev-parse", "--verify", commit + "^{tree}")

    endpoint = f"repos/{repository}/actions/artifacts/{artifact}"
    metadata = json.loads(output("gh", "api", endpoint))
    require(metadata["id"] == int(artifact) and
            metadata["name"] == f"source-onboarding-governed-image-{run}" and
            metadata["expired"] is False, "Wrong or expired Onboarding artifact")
    producer = metadata["workflow_run"]
    require(producer["id"] == int(run) and producer["head_sha"] == commit,
            "Artifact producer run or PR head mismatch")

    bundle = work / "artifact.zip"
    with bundle.open("wb") as stream:
        subprocess.run(["gh", "api", endpoint + "/zip"], stdout=stream, check=True)
    require(sha256(bundle) == os.environ["PREDECESSOR_ZIP_SHA256"],
            "Governed artifact ZIP checksum mismatch")

    archive = work / "image.tar.gz"
    with ZipFile(bundle) as files:
        manifest = json.loads(files.read("manifest.json"))
        revision = manifest["testedCommit"]  # PR test merge, distinct from the reviewed head.
        tag = f"ouf-source-onboarding:{revision}"
        require(manifest["schema"] == "ouf.onboarding-tested-image.v1" and
                manifest["repository"] == repository, "Unexpected image producer")
        require(manifest["reviewedSourceCommit"] == commit and manifest["testedTree"] == tree and
                manifest["workflowRunId"] == run, "Predecessor commit, tree or run mismatch")
        require(re.fullmatch(r"[0-9a-f]{40}", revision) and manifest["imageRevisionLabel"] == revision
                and manifest["imageTag"] == tag and manifest["imageId"] == image,
                "Predecessor image identity mismatch")
        require(manifest["archiveName"] == archive.name, "Unexpected image archive")
        with files.open(archive.name) as source, archive.open("wb") as dest:
            shutil.copyfileobj(source, dest)
    require(sha256(archive) == manifest["archiveSha256"], "Image archive checksum mismatch")

    subprocess.run(["docker", "load", "-i", archive], check=True, stdout=sys.stderr)
    require(output("docker", "image", "inspect", tag, "--format", "{{.Id}}") == image,
            "Loaded image ID differs from the installed release pin")
    require(output("docker", "image", "inspect", image, "--format",
                   '{{index .Config.Labels "org.opencontainers.image.revision"}}') == revision,
            "Loaded image revision differs from the tested commit")
    return image


if __name__ == "__main__":
    with TemporaryDirectory() as directory:
        print("image=" + load_predecessor(Path(directory)))
