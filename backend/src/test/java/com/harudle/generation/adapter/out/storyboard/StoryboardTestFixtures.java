package com.harudle.generation.adapter.out.storyboard;

public final class StoryboardTestFixtures {

    private StoryboardTestFixtures() {
    }

    public static String storyboardJson() {
        return """
                {
                  "title": "발표 직전",
                  "cast_continuity": "The protagonist and a colleague remain distinct.",
                  "panels": [
                    {"panel_number":1,"story_role":"setup","caption":"준비 완료",
                     "scene":"Two people sit at a desk.","characters":"The protagonist sits beside a colleague.",
                     "emotion":"Both look calm.","props":["laptop"]},
                    {"panel_number":2,"story_role":"action","caption":"지금 업데이트?",
                     "scene":"The laptop begins updating.","characters":"Both people look at the laptop.",
                     "emotion":"Both look surprised.","props":["laptop"]},
                    {"panel_number":3,"story_role":"escalation","caption":"작은 화면 출동",
                     "scene":"The colleague holds a phone.","characters":"The protagonist begins presenting.",
                     "emotion":"Both look focused.","props":["phone"]},
                    {"panel_number":4,"story_role":"resolution","caption":"이제야 준비 완료",
                     "scene":"The people return to the desk.","characters":"The two people laugh.",
                     "emotion":"Both look amused.","props":["laptop"]}
                  ],
                  "visual_plan":{"environment_rule":"All panels remain in the same office.","focal_colors":[]}
                }
                """;
    }
}
