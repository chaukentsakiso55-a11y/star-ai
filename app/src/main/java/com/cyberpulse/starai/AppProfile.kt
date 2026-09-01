package com.cyberpulse.starai

val appSpec = AppSpec(
    name = "Star AI",
    shortName = "ST",
    tagline = "Ideas with direction. Intelligence with purpose.",
    hero = "Capture an idea, shape the plan and move from possibility to confident execution.",
    primary = 0xFFFFD166,
    secondary = 0xFF8A66FF,
    focusLabel = "Creation sprint",
    logHint = "Capture an idea, question or next step",
    features = listOf(
        AppFeature("Star Chat", "Prepare a clean conversational workspace.", "CHAT"),
        AppFeature("Idea Forge", "Turn raw ideas into structured possibilities.", "CREATE"),
        AppFeature("Mission Planner", "Convert ambitions into concrete next actions.", "PLAN"),
        AppFeature("Knowledge", "Keep useful discoveries organized locally.", "LEARN"),
        AppFeature("Focus", "Give important creative work protected time.", "BUILD"),
        AppFeature("Memory", "Prepare user-controlled context for later AI.", "LOCAL")
    ),
    metrics = listOf(
        AppMetric("Workspace", "Ready"),
        AppMetric("Ideas", "Local"),
        AppMetric("Memory", "Private"),
        AppMetric("AI providers", "Phase 2")
    ),
    about = "Star AI is a Cyber Pulse creative intelligence product. The foundation is designed for multiple AI providers later without embedding a provider or key today."
)
