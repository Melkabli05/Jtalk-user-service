package com.jtalk.userservice;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The shape of the service, as in the live communication service: controller -> service -> repository -> domain, nothing reaching upwards,
 * every kind of class in its own package with its own name, and the implementation details (controllers, service implementations)
 * invisible outside their package.
 */
@AnalyzeClasses(packages = "com.jtalk.userservice", importOptions = {ImportOption.DoNotIncludeTests.class, ArchitectureTest.OurCodeOnly.class})
class ArchitectureTest {

    /** Micronaut generates classes ({@code $X$Definition}, {@code X$Intercepted}, ...) beside ours at compile time; the rules are about what we wrote. */
    static final class OurCodeOnly implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("$");
        }
    }

    private static final String ROOT = "com.jtalk.userservice.";
    private static final String CONFIG = ROOT + "config..";
    private static final String CONTROLLER = ROOT + "controller..";
    private static final String DTO = ROOT + "dto..";
    private static final String DTO_REQUEST = ROOT + "dto.request..";
    private static final String DTO_RESPONSE = ROOT + "dto.response..";
    private static final String SERVICE = ROOT + "service..";
    private static final String SERVICE_API = ROOT + "service";
    private static final String SERVICE_IMPL = ROOT + "service.impl..";
    private static final String REPOSITORY = ROOT + "repository..";
    private static final String DOMAIN = ROOT + "domain..";
    private static final String ENTITY = ROOT + "domain.entity..";

    // ---- layering -----------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule controllers_use_services_not_repositories = noClasses()
            .that().resideInAPackage(CONTROLLER)
            .should().dependOnClassesThat().resideInAnyPackage(REPOSITORY);

    @ArchTest
    static final ArchRule services_do_not_use_controllers = noClasses()
            .that().resideInAPackage(SERVICE)
            .should().dependOnClassesThat().resideInAPackage(CONTROLLER);

    @ArchTest
    static final ArchRule repositories_do_not_use_upper_layers = noClasses()
            .that().resideInAPackage(REPOSITORY)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONTROLLER, DTO);

    @ArchTest
    static final ArchRule the_domain_is_plain = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER, DTO);

    @ArchTest
    static final ArchRule dtos_do_not_use_services_or_repositories = noClasses()
            .that().resideInAPackage(DTO)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER);

    @ArchTest
    static final ArchRule config_depends_on_nothing_of_ours = noClasses()
            .that().resideInAPackage(CONFIG)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER, DTO, DOMAIN);

    @ArchTest
    static final ArchRule only_interfaces_are_used_outside_the_implementation_package = noClasses()
            .that().resideOutsideOfPackages(SERVICE_IMPL)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE_IMPL);

    @ArchTest
    static final ArchRule no_package_cycles = slices().matching(ROOT + "(*)..").should().beFreeOfCycles();

    // ---- each kind of class lives in its own package, under its own name -----------------------------

    @ArchTest
    static final ArchRule controllers_are_named_and_hidden = classes()
            .that().areAnnotatedWith(Controller.class)
            .should().resideInAPackage(CONTROLLER)
            .andShould().haveSimpleNameEndingWith("Controller")
            .andShould().notBePublic();

    @ArchTest
    static final ArchRule every_controller_is_secured = classes()
            .that().areAnnotatedWith(Controller.class)
            .should().beAnnotatedWith(Secured.class);

    @ArchTest
    static final ArchRule service_interfaces_are_named_and_validated = classes()
            .that().resideInAPackage(SERVICE_API).and().areInterfaces()
            .should().haveSimpleNameEndingWith("Service")
            .andShould().beAnnotatedWith(Validated.class);

    @ArchTest
    static final ArchRule service_implementations_are_named_and_hidden = classes()
            .that().resideInAPackage(SERVICE_IMPL).and().areTopLevelClasses().and().areNotInterfaces()
            .and().haveSimpleNameEndingWith("Impl")
            .should().notBePublic();

    @ArchTest
    static final ArchRule nothing_in_the_service_implementation_package_is_public = classes()
            .that().resideInAPackage(SERVICE_IMPL)
            .should().notBePublic();

    @ArchTest
    static final ArchRule repositories_are_named_repositories = classes()
            .that().resideInAPackage(REPOSITORY).and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule entities_live_in_the_entity_package = classes()
            .that().areAnnotatedWith(MappedEntity.class)
            .should().resideInAPackage(ENTITY);

    @ArchTest
    static final ArchRule requests_and_responses_are_named_and_placed = classes()
            .that().resideInAPackage(DTO_REQUEST).should().haveSimpleNameEndingWith("Request")
            .andShould().beRecords();

    @ArchTest
    static final ArchRule responses_are_named_and_placed = classes()
            .that().resideInAPackage(DTO_RESPONSE).should().haveSimpleNameEndingWith("Response")
            .andShould().beRecords();

    // ---- hygiene -------------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule constructor_injection_only = noFields().should().beAnnotatedWith(Inject.class);

    @ArchTest
    static final ArchRule no_standard_streams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
