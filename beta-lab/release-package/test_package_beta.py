import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("package_beta", Path(__file__).with_name("package_beta.py"))
package_beta = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(package_beta)


class ClassSignatureTest(unittest.TestCase):
    def test_nested_generic_suffix_is_not_treated_as_a_class_type(self):
        signature = "<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/lang/Object;Lpkg/ScopedRegistry<TK;TV;>.Lease;"
        self.assertEqual(package_beta.class_signature_references(signature),
                         {"java/lang/Object", "pkg/ScopedRegistry"})

    def test_actual_class_types_after_type_variables_are_retained(self):
        signature = "Ljava/util/Map<TK;TV;>;Ljava/util/List<Lpkg/Module;>;"
        self.assertEqual(package_beta.class_signature_references(signature),
                         {"java/util/Map", "java/util/List", "pkg/Module"})


if __name__ == "__main__":
    unittest.main()
