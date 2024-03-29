package parsers.python

import core.domain.dependency.*
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.*

import flatspec.*
import matchers.*

class RequirementsTxtSpec extends AnyFlatSpec with should.Matchers:
  import RequirementsTxt.*

  "Parse requirements" should "ignore commented out depenencies" in {
    val requirements = """
    | Django==1.2.3
    | # Flask==4.5.6
    """.stripMargin

    val dependencies = extract(requirements)
    dependencies should contain(
      Dependency(
        name = "Django",
        currentVersion = Some("1.2.3")
      )
    )
    dependencies should have length 1
  }

  it should "set current version to None when it's missing" in {
    val requirements = """
    | Django
    """.stripMargin

    val dependencies = extract(requirements)
    dependencies should contain(
      Dependency(
        name = "Django",
        currentVersion = None
      )
    )
    dependencies should have length 1
  }

  it should "extract a name that uses special characters" in {
    val requirements = """
    | django-autocomplete-light
    """.stripMargin

    val dependencies = extract(requirements)
    dependencies should contain(
      Dependency(
        name = "django-autocomplete-light",
        currentVersion = None
      )
    )
    dependencies should have length 1
  }

  it should "ignore comment to dependency" in {
    val comment = "my comment here"
    val requirements = s"""
    | django-autocomplete-light==1.2.3 # $comment
    """

    val dependencies = extract(requirements)

    dependencies should have length 1
    dependencies.foreach(dependency =>
      dependency.currentVersion.value shouldNot contain(comment)
    )
  }

  it should "detect * in version" in {
    val requirements = """
    | django-autocomplete-light==1.2.*
    """

    val dependencies = extract(requirements)

    dependencies should contain only (Dependency(
      "django-autocomplete-light",
      Some("1.2.*")
    ))
  }

  it should "convert >=version to ^version" in {
    val requirements = """
    | library>=1.2.3
    """

    val dependencies = extract(requirements)

    dependencies should contain only (Dependency("library", Some("^1.2.3")))
  }

  it should "convert ~=version to ~version" in {
    val requirements = """
    | library~=1.2.3
    """

    val dependencies = extract(requirements)

    dependencies should contain only (Dependency("library", Some("~1.2.3")))
  }

  it should "detect dependency with extras in the name" in:
    val requirements = """
    | library[foo]==1.2.3
    """

    val dependencies = extract(requirements)

    dependencies should contain only (Dependency("library", Some("1.2.3")))

  it should "detect dependency with many extras in the name" in:
    val requirements = """
    | library[foo,bar,baz]==1.2.3
    """

    val dependencies = extract(requirements)

    dependencies should contain only (Dependency("library", Some("1.2.3")))
