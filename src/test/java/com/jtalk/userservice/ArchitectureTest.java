package com.jtalk.userservice;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layering, as in the live communication service: controller -> service -> repository -> domain. Nothing reaches upwards, and only service
 * interfaces (not their implementations) are used across packages.
 */
@AnalyzeClasses(packages = "com.jtalk.userservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.jtalk.userservice.";
    private static final String CONTROLLER = ROOT + "controller..";
    private static final String DTO = ROOT + "dto..";
    private static final String SERVICE = ROOT + "service..";
    private static final String SERVICE_IMPL = ROOT + "service.impl..";
    private static final String REPOSITORY = ROOT + "repository..";
    private static final String DOMAIN = ROOT + "domain..";

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
    static final ArchRule only_interfaces_are_used_outside_the_implementation_package = noClasses()
            .that().resideOutsideOfPackages(SERVICE_IMPL)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE_IMPL);
}
