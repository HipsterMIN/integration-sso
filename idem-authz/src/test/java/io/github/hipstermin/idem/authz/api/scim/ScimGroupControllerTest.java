package io.github.hipstermin.idem.authz.api.scim;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.authz.api.GlobalExceptionHandler;
import io.github.hipstermin.idem.authz.application.ScimGroupService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ScimGroupControllerTest {

    private MockMvc mockMvc;
    private ScimGroupService scimGroupService;
    private final ObjectMapper om = new ObjectMapper();

    private static final String GROUP_ID = "GOV_SMES:MANAGER";

    @BeforeEach
    void setUp() {
        scimGroupService = Mockito.mock(ScimGroupService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ScimGroupController(scimGroupService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void get_returnsGroupWithSchema() throws Exception {
        when(scimGroupService.getGroup(GROUP_ID))
                .thenReturn(ScimGroup.of(GROUP_ID, GROUP_ID, List.of(ScimMember.of("u1"))));

        mockMvc.perform(get("/scim/v2/Groups/{id}", GROUP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemas[0]").value(ScimGroup.SCHEMA))
                .andExpect(jsonPath("$.id").value(GROUP_ID))
                .andExpect(jsonPath("$.members[0].value").value("u1"));
    }

    @Test
    void list_returnsListResponse() throws Exception {
        when(scimGroupService.listGroups("GOV_SMES"))
                .thenReturn(List.of(ScimGroup.of(GROUP_ID, GROUP_ID, List.of())));

        mockMvc.perform(get("/scim/v2/Groups").param("agencyCode", "GOV_SMES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemas[0]").value(ScimListResponse.SCHEMA))
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.Resources[0].id").value(GROUP_ID));
    }

    @Test
    void put_replacesMembers() throws Exception {
        when(scimGroupService.replaceMembers(eq(GROUP_ID), any(), any()))
                .thenReturn(ScimGroup.of(GROUP_ID, GROUP_ID, List.of(ScimMember.of("u2"))));
        String body = om.writeValueAsString(ScimGroup.of(GROUP_ID, GROUP_ID, List.of(ScimMember.of("u2"))));

        mockMvc.perform(put("/scim/v2/Groups/{id}", GROUP_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].value").value("u2"));

        verify(scimGroupService).replaceMembers(eq(GROUP_ID), any(), any());
    }

    @Test
    void patch_addMember() throws Exception {
        when(scimGroupService.patch(eq(GROUP_ID), any(), any()))
                .thenReturn(ScimGroup.of(GROUP_ID, GROUP_ID, List.of(ScimMember.of("u9"))));
        String body = """
            {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
             "Operations":[{"op":"add","path":"members","value":[{"value":"u9"}]}]}
            """;

        mockMvc.perform(patch("/scim/v2/Groups/{id}", GROUP_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(scimGroupService).patch(eq(GROUP_ID), any(), any());
    }
}
