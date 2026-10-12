plugins {
    id("hearth.android.library")
}

// The aggregator: DomainContainer and what merges areas (Search's one list). The logic lives in :domain:*.
dependencies {
    api(project(":core:data"))
    api(project(":domain:music"))
    api(project(":domain:spoken"))
    api(project(":domain:requests"))
    api(project(":domain:update"))
}
