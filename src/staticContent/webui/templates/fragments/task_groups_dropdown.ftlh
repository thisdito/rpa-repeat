<#macro fragment>
    <a href="/task-groups" class="btn <#if taskGroup.enabled> btn-default <#else> btn-danger </#if>">${taskGroup.current}</a>
    <button type="button" class="btn btn-default dropdown-toggle" data-toggle="dropdown" aria-haspopup="true" aria-expanded="false">
        <span class="caret"></span>
        <span class="sr-only">Toggle Dropdown</span>
    </button>
    <ul id="task-groups-dropdown" class="dropdown-menu">
        <#list taskGroup.groups as group>
        <li id="${group.id}"><a href="#">${group.name}</a></li>
        </#list>
    </ul>
</#macro>

<@fragment/>