"""Validate CSV proposals against the published Draft 2020-12 contract with jsonschema."""
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[1]


class ManagedExecutionProposalSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = json.loads((ROOT / "docs/contracts/managed-file-execution-proposal.schema.json").read_text("utf-8"))
        Draft202012Validator.check_schema(schema)
        cls.validator = Draft202012Validator(schema)
        resolution = json.loads((ROOT / "src/test/resources/identity-governed-proposal-v1.json").read_text("utf-8"))
        publication = "00000000-0000-4000-8000-000000000002"
        resolution["governedIdentity"]["signals"][0]["semanticRef"] = f"urn:key@{publication}"
        cls.proposal = {
            "semanticBindings": [{
                "semanticId": "urn:ontology", "semanticVersion": "1",
                "revisionId": "00000000-0000-4000-8000-000000000001", "publicationSetId": publication,
            }],
            "semanticPublicationSetRef": publication,
            "udp": {
                "resolution": resolution,
                "materialization": {
                    "policyRef": "urn:authority", "bitemporalProperties": [], "checkpointInterval": 1,
                    "properties": [{
                        "sourceField": "urn:key", "propertyIri": "urn:key",
                        "datatype": "http://www.w3.org/2001/XMLSchema#string",
                        "accessLabel": "RESTRICTED", "authorityOrder": [],
                    }],
                },
            },
        }

    def test_limits_may_be_omitted_partial_or_complete(self):
        for fields in ({}, {"limits": {"maxRows": 100}},
                       {"limits": {"maxRows": 100, "maxColumns": 10, "maxCellChars": 1000}}):
            with self.subTest(fields=fields):
                self.validator.validate({**self.proposal, **fields})

    def test_unknown_limit_is_rejected(self):
        errors = list(self.validator.iter_errors({**self.proposal, "limits": {"maxRow": 100}}))
        self.assertEqual([(list(error.path), error.validator) for error in errors],
                         [(["limits"], "additionalProperties")])
