package net.transgressoft.fleet.common

/** Classification of physical and postal addresses. */
enum class AddressType {
    PHYSICAL,
    POSTAL,
    BILLING,
    SHIPPING,
    HOME,
    WORK,
    OTHER
}

/** Classification of contact information entries. */
enum class ContactType {
    PHONE,
    EMAIL,
    FAX,
    MOBILE,
    WEBSITE,
    OTHER
}

/** Relationship types between companies. */
enum class RelationshipType {
    PARENT,
    BROKER,
    AGENT,
    PARTNER
}

/** Person salutation. */
enum class Salutation {
    MR,
    MS,
    DIVERSE
}

/** Academic or professional title. */
enum class Title {
    DR,
    PROF,
    PROF_DR,
    DIPL_ING
}

/** Insurance policy type. */
enum class PolicyType {
    LIABILITY,
    COMPREHENSIVE,
    PARTIAL,
    OTHER
}

/** Vehicle contract status. */
enum class ContractStatus {
    RUNNING,
    COMPLETED
}

/** Vehicle assignment type — determines whether the target is a person or company. */
enum class AssignmentType {
    DRIVER,
    USER,
    HOLDER
}

/** Role of a person within a company. */
enum class CompanyPersonRole {
    EMPLOYEE,
    DRIVER,
    MANAGING_DIRECTOR,
    CONTACT_PERSON
}