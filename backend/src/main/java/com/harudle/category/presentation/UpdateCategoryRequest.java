package com.harudle.category.presentation;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;

public class UpdateCategoryRequest {

    private String name;

    @PositiveOrZero(message = "표시 순서는 0 이상이어야 합니다.")
    @JsonDeserialize(using = CreateCategoryRequest.SortOrderDeserializer.class)
    private Integer sortOrder;

    private Boolean active;

    public String getName() {
        return name;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String name) {
        this.name = name;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getActive() {
        return active;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setActive(Boolean active) {
        this.active = active;
    }
}
